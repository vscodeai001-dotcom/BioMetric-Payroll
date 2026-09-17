using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Payroll.Web.Services;

namespace Payroll.Web.Controllers;

[ApiController]
[Route("api/mobile/admin/users")]
[Authorize(AuthenticationSchemes = "MobileBearer", Roles = "SuperAdmin")]
public sealed class MobileAdminUserManagementController : ControllerBase
{
    private readonly FirebaseUserManagementService _service;

    public MobileAdminUserManagementController(FirebaseUserManagementService service)
        => _service = service;

    [HttpPost]
    public async Task<IActionResult> Create([FromBody] CreateUserRequest request, CancellationToken ct)
    {
        var result = await _service.CreateAsync(request.Email, request.Password, request.Role, request.EmployeeId, request.DisplayName, ct);
        return result.Success ? Ok(result) : BadRequest(result);
    }

    [HttpPut("{firebaseUid}/role")]
    public async Task<IActionResult> ChangeRole(string firebaseUid, [FromBody] ChangeRoleRequest request, CancellationToken ct)
    {
        var result = await _service.ChangeRoleAsync(firebaseUid, request.Role, ct);
        return result.Success ? Ok(result) : BadRequest(result);
    }

    [HttpPut("{firebaseUid}/disabled")]
    public async Task<IActionResult> SetDisabled(string firebaseUid, [FromBody] DisabledUserRequest request, CancellationToken ct)
    {
        var result = await _service.SetDisabledAsync(firebaseUid, request.Disabled, ct);
        return result.Success ? Ok(result) : BadRequest(result);
    }

    [HttpDelete("{firebaseUid}")]
    public async Task<IActionResult> Delete(string firebaseUid, CancellationToken ct)
    {
        var result = await _service.DeleteAsync(firebaseUid, ct);
        return result.Success ? Ok(result) : BadRequest(result);
    }

    public sealed record CreateUserRequest(string Email, string Password, string Role = "Employee", int EmployeeId = 0, string? DisplayName = null);
    public sealed record ChangeRoleRequest(string Role);
    public sealed record DisabledUserRequest(bool Disabled);
}
