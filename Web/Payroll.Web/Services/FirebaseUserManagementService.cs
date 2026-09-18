using System.Text.Json;
using FirebaseAdmin.Auth;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Payroll.Shared.Data;

namespace Payroll.Web.Services;

/// <summary>
/// Privileged user-governance boundary. Only the server may perform Firebase
/// Authentication account lifecycle operations. Clients never write/delete
/// Firebase Auth users directly.
/// </summary>
public sealed class FirebaseUserManagementService
{
    private readonly IDbContextFactory<AppDbContext> _dbFactory;
    private readonly UserManager<IdentityUser> _users;
    private readonly FirebaseRealtimeService _firebase;
    private readonly ILogger<FirebaseUserManagementService> _logger;

    public FirebaseUserManagementService(
        IDbContextFactory<AppDbContext> dbFactory,
        UserManager<IdentityUser> users,
        FirebaseRealtimeService firebase,
        ILogger<FirebaseUserManagementService> logger)
    {
        _dbFactory = dbFactory;
        _users = users;
        _firebase = firebase;
        _logger = logger;
    }

    public static bool IsSupportedRole(string role) =>
        role.Equals("Admin", StringComparison.OrdinalIgnoreCase) ||
        role.Equals("Employee", StringComparison.OrdinalIgnoreCase);

    public async Task<UserManagementResult> CreateAsync(
        string email,
        string password,
        string role,
        int employeeId,
        string? displayName,
        CancellationToken ct)
    {
        email = email.Trim();
        role = NormalizeRole(role);
        if (!IsSupportedRole(role))
            return Fail("Only Admin and Employee accounts can be created here.");
        if (string.IsNullOrWhiteSpace(password) || password.Length < 6)
            return Fail("Password must be at least 6 characters.");
        if (role == "Admin" && employeeId > 0)
            return Fail("Admin accounts cannot be linked to an Employee record.");

        // Employee accounts must always correspond to a saved Employee master
        // record. Do not create empty/unlinked Employee users.
        if (role == "Employee" && employeeId <= 0)
            return Fail("Employee accounts must be linked to a saved Employee record.");

        await using var db = await _dbFactory.CreateDbContextAsync(ct);
        var existingIdentity = await _users.FindByEmailAsync(email);
        if (existingIdentity != null)
            return Fail("A Web Identity account with this email already exists.");

        Employee? employee = null;
        if (employeeId > 0)
        {
            employee = await db.Employees.FirstOrDefaultAsync(e => e.EmployeeID == employeeId && !e.IsDeleted, ct);
            if (employee == null)
                return Fail("Employee record was not found.");
            if (!string.IsNullOrWhiteSpace(employee.AspNetUserId))
                return Fail("The selected Employee is already linked to a user.");
        }

        var identity = new IdentityUser { Email = email, UserName = email, EmailConfirmed = true };
        var identityResult = await _users.CreateAsync(identity, password);
        if (!identityResult.Succeeded)
            return Fail(string.Join(", ", identityResult.Errors.Select(e => e.Description)));

        try
        {
            var roleResult = await _users.AddToRoleAsync(identity, role);
            if (!roleResult.Succeeded)
                throw new InvalidOperationException(string.Join(", ", roleResult.Errors.Select(e => e.Description)));

            if (employee != null)
            {
                employee.Email = email;
                employee.AspNetUserId = identity.Id;
                await db.SaveChangesAsync(ct);
            }

            var firebaseUid = await _firebase.EnsureFirebaseUserAsync(
                email,
                password,
                role,
                employee?.EmployeeID ?? 0,
                displayName ?? employee?.Name ?? email,
                existingUid: null,
                updatePasswordIfExisting: true, // MIRROR: Ensure Firebase password matches Web/SQL password
                cancellationToken: ct);

            if (string.IsNullOrWhiteSpace(firebaseUid))
                throw new InvalidOperationException("Firebase Authentication provisioning failed.");

            await SyncProfileAsync(firebaseUid, email, role, employee, enabled: true, ct);
            return new UserManagementResult(true, firebaseUid, identity.Id, "User created and synchronized.");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "User creation failed for {Email}; compensating local Identity state.", email);
            if (employee != null)
            {
                employee.AspNetUserId = null;
                employee.Email = null;
                await db.SaveChangesAsync(CancellationToken.None);
            }
            await _users.DeleteAsync(identity);
            return Fail(ex.Message);
        }
    }

    public async Task<UserManagementResult> ChangeRoleAsync(string firebaseUid, string newRole, CancellationToken ct)
    {
        newRole = NormalizeRole(newRole);
        if (!IsSupportedRole(newRole))
            return Fail("Only Admin and Employee roles can be assigned.");
        if (string.IsNullOrWhiteSpace(firebaseUid))
            return Fail("Firebase UID is required.");

        var auth = await _firebase.GetFirebaseAuthAsync(ct) ?? throw new InvalidOperationException("Firebase Authentication is not configured.");
        UserRecord firebaseUser;
        try { firebaseUser = await auth.GetUserAsync(firebaseUid, ct); }
        catch (FirebaseAuthException ex) when (ex.AuthErrorCode == AuthErrorCode.UserNotFound) { return Fail("Firebase user was not found."); }

        if (firebaseUser.Email?.Equals(FirebaseAuthSecurityConstants.CanonicalSuperAdminEmail, StringComparison.OrdinalIgnoreCase) == true)
            return Fail("The canonical SuperAdmin role cannot be changed.");

        await using var db = await _dbFactory.CreateDbContextAsync(ct);
        var identity = !string.IsNullOrWhiteSpace(firebaseUser.Email)
            ? await _users.FindByEmailAsync(firebaseUser.Email)
            : null;
        Employee? employee = null;
        if (identity != null)
            employee = await db.Employees.FirstOrDefaultAsync(e => e.AspNetUserId == identity.Id && !e.IsDeleted, ct);

        if (newRole == "Admin" && employee != null)
            return Fail("An Employee-linked account cannot be changed to Admin.");
        if (newRole == "Employee" && employee == null)
            return Fail("Employee role requires a linked Employee record.");

        var oldRoles = identity == null ? new List<string>() : (await _users.GetRolesAsync(identity)).ToList();
        if (identity != null)
        {
            if (oldRoles.Any())
            {
                var remove = await _users.RemoveFromRolesAsync(identity, oldRoles);
                if (!remove.Succeeded) return Fail(string.Join(", ", remove.Errors.Select(e => e.Description)));
            }
            var add = await _users.AddToRoleAsync(identity, newRole);
            if (!add.Succeeded) return Fail(string.Join(", ", add.Errors.Select(e => e.Description)));
        }

        try
        {
            await auth.SetCustomUserClaimsAsync(firebaseUid, new Dictionary<string, object>
            {
                ["role"] = newRole,
                ["employee_id"] = employee?.EmployeeID ?? 0,
                ["owner_uid"] = _firebase.ResolveOwnerUid(firebaseUid, newRole)
            }, ct);
            await SyncProfileAsync(firebaseUid, firebaseUser.Email ?? string.Empty, newRole, employee, !firebaseUser.Disabled, ct);
            return new UserManagementResult(true, firebaseUid, identity?.Id, "Role changed and claims synchronized.");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Role synchronization failed for Firebase UID {Uid}; reverting Identity roles.", firebaseUid);
            if (identity != null)
            {
                var now = await _users.GetRolesAsync(identity);
                if (now.Any()) await _users.RemoveFromRolesAsync(identity, now);
                if (oldRoles.Any()) await _users.AddToRolesAsync(identity, oldRoles);
            }
            return Fail(ex.Message);
        }
    }

    public async Task<UserManagementResult> SetDisabledAsync(string firebaseUid, bool disabled, CancellationToken ct)
    {
        var auth = await _firebase.GetFirebaseAuthAsync(ct) ?? throw new InvalidOperationException("Firebase Authentication is not configured.");
        UserRecord user;
        try { user = await auth.GetUserAsync(firebaseUid, ct); }
        catch (FirebaseAuthException ex) when (ex.AuthErrorCode == AuthErrorCode.UserNotFound) { return Fail("Firebase user was not found."); }

        if (user.Email?.Equals(FirebaseAuthSecurityConstants.CanonicalSuperAdminEmail, StringComparison.OrdinalIgnoreCase) == true && disabled)
            return Fail("The canonical SuperAdmin cannot be disabled.");

        await auth.UpdateUserAsync(new UserRecordArgs { Uid = firebaseUid, Disabled = disabled }, ct);
        if (disabled)
            await auth.RevokeRefreshTokensAsync(firebaseUid, ct);

        await using var db = await _dbFactory.CreateDbContextAsync(ct);
        var identity = !string.IsNullOrWhiteSpace(user.Email) ? await _users.FindByEmailAsync(user.Email) : null;
        if (identity != null)
        {
            identity.LockoutEnabled = true;
            identity.LockoutEnd = disabled ? DateTimeOffset.MaxValue : null;
            var result = await _users.UpdateAsync(identity);
            if (!result.Succeeded)
                return Fail(string.Join(", ", result.Errors.Select(e => e.Description)));
        }

        var employee = identity == null ? null : await db.Employees.FirstOrDefaultAsync(e => e.AspNetUserId == identity.Id && !e.IsDeleted, ct);
        await SyncProfileAsync(firebaseUid, user.Email ?? string.Empty, ResolveRole(user), employee, !disabled, ct);
        return new UserManagementResult(true, firebaseUid, identity?.Id, disabled ? "User disabled and sessions revoked." : "User enabled.");
    }

    public async Task<UserManagementResult> DeleteAsync(string firebaseUid, CancellationToken ct)
    {
        var auth = await _firebase.GetFirebaseAuthAsync(ct) ?? throw new InvalidOperationException("Firebase Authentication is not configured.");
        UserRecord user;
        try { user = await auth.GetUserAsync(firebaseUid, ct); }
        catch (FirebaseAuthException ex) when (ex.AuthErrorCode == AuthErrorCode.UserNotFound) { return Fail("Firebase user was not found."); }

        if (user.Email?.Equals(FirebaseAuthSecurityConstants.CanonicalSuperAdminEmail, StringComparison.OrdinalIgnoreCase) == true)
            return Fail("The canonical SuperAdmin cannot be deleted.");

        await auth.RevokeRefreshTokensAsync(firebaseUid, ct);
        await auth.DeleteUserAsync(firebaseUid, ct);

        await using var db = await _dbFactory.CreateDbContextAsync(ct);
        var identity = !string.IsNullOrWhiteSpace(user.Email) ? await _users.FindByEmailAsync(user.Email) : null;
        if (identity != null)
        {
            var employee = await db.Employees.FirstOrDefaultAsync(e => e.AspNetUserId == identity.Id, ct);
            if (employee != null)
            {
                employee.AspNetUserId = null;
                employee.Email = null;
            }
            var result = await _users.DeleteAsync(identity);
            if (!result.Succeeded)
                return Fail("Firebase account was deleted, but Web Identity cleanup failed: " + string.Join(", ", result.Errors.Select(e => e.Description)));
            await db.SaveChangesAsync(ct);
        }

        await _firebase.DeleteGlobalRecordAsync($"user_profiles/{firebaseUid}", ct);
        return new UserManagementResult(true, firebaseUid, identity?.Id, "Firebase Auth, sessions, profile and Web Identity synchronized for deletion.");
    }

    private async Task SyncProfileAsync(string uid, string email, string role, Employee? employee, bool enabled, CancellationToken ct)
    {
        var profile = new Dictionary<string, object?>
        {
            ["uid"] = uid,
            ["email"] = email,
            ["name"] = employee?.Name ?? email,
            ["phone"] = "",
            ["employeeId"] = employee?.EmployeeID.ToString() ?? "",
            ["role"] = role,
            ["enabled"] = enabled,
            ["ownerUid"] = _firebase.ResolveOwnerUid(uid, role),
            ["dataLastModified"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
        };
        if (!await _firebase.SetGlobalRecordAsync($"user_profiles/{uid}", profile, ct))
            throw new InvalidOperationException("Firebase user profile synchronization failed.");
    }

    private static string NormalizeRole(string role) =>
        role.Equals("SuperAdmin", StringComparison.OrdinalIgnoreCase) ? "SuperAdmin" :
        role.Equals("Admin", StringComparison.OrdinalIgnoreCase) ? "Admin" : "Employee";

    private static string ResolveRole(UserRecord user)
    {
        if (user.CustomClaims != null && user.CustomClaims.TryGetValue("role", out var value))
            return value?.ToString() ?? "Employee";
        return "Employee";
    }

    private static UserManagementResult Fail(string message) => new(false, null, null, message);
}

public sealed record UserManagementResult(bool Success, string? FirebaseUid, string? IdentityUserId, string Message);

public static class FirebaseAuthSecurityConstants
{
    public const string CanonicalSuperAdminEmail = "prakashshiva368@gmail.com";
}
