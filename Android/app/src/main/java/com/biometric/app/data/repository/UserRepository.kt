package com.biometric.app.data.repository

import com.biometric.app.data.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor() {

    data class UserViewModel(
        val userId: String,
        val email: String,
        val role: String,
        val employeeName: String?
    )

    suspend fun getAllUsers(): List<UserViewModel> = withContext(Dispatchers.IO) {
        val users = mutableListOf<UserViewModel>()
        DatabaseManager.getConnection()?.use { conn ->
            val sql = """
                SELECT u."Id", u."Email", r."Name" as RoleName, e.name as EmployeeName
                FROM public."AspNetUsers" u
                LEFT JOIN public."AspNetUserRoles" ur ON u."Id" = ur."UserId"
                LEFT JOIN public."AspNetRoles" r ON ur."RoleId" = r."Id"
                LEFT JOIN public.employees e ON u."Id" = e."AspNetUserId"
                ORDER BY u."Email"
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        users.add(UserViewModel(
                            userId = rs.getString("Id"),
                            email = rs.getString("Email"),
                            role = rs.getString("RoleName") ?: "Employee",
                            employeeName = rs.getString("EmployeeName")
                        ))
                    }
                }
            }
        }
        users
    }

    suspend fun deleteUser(userId: String) = withContext(Dispatchers.IO) {
        DatabaseManager.getConnection()?.use { conn ->
            conn.autoCommit = false
            try {
                // 1. Unlink employee
                conn.prepareStatement("UPDATE public.employees SET \"AspNetUserId\" = NULL, \"Email\" = NULL WHERE \"AspNetUserId\" = ?")
                    .use { stmt -> stmt.setString(1, userId); stmt.executeUpdate() }
                
                // 2. Delete user (Cascades will handle roles usually if configured, but let's be safe)
                conn.prepareStatement("DELETE FROM public.\"AspNetUserRoles\" WHERE \"UserId\" = ?")
                    .use { stmt -> stmt.setString(1, userId); stmt.executeUpdate() }
                
                conn.prepareStatement("DELETE FROM public.\"AspNetUsers\" WHERE \"Id\" = ?")
                    .use { stmt -> stmt.setString(1, userId); stmt.executeUpdate() }
                
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }
}
