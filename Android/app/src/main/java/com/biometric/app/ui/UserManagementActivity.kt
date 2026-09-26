package com.biometric.app.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.api.AdminCreateUserRequest
import com.biometric.app.data.entity.UserRole
import com.biometric.app.data.repository.UserRepository
import com.biometric.app.databinding.ActivityUserManagementBinding
import com.biometric.app.databinding.DialogAddUserBinding
import com.biometric.app.databinding.ItemUserManagementBinding
import com.biometric.app.ui.viewmodel.UserViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class UserManagementActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityUserManagementBinding
    private val viewModel: UserViewModel by viewModels()
    private var isSuperAdmin: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        applyWindowInsets(binding.clUserManagementRoot, binding.appBar)

        // Web parity: User & Role Management is accessible by SuperAdmin and Company Admin.
        val role = getSharedPreferences("auth_prefs", MODE_PRIVATE)
            .getString("user_role", UserRole.Employee.name)
            ?.trim()
            ?.uppercase()
        isSuperAdmin = role == UserRole.SuperAdmin.name.uppercase()
        val isAdmin = isSuperAdmin || role == UserRole.Admin.name.uppercase()

        if (!isAdmin) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Access Restricted 🛡️")
                .setMessage("User and Role Management is available only to Administrators.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .setOnDismissListener { if (!isFinishing) finish() }
                .show()
            return
        }

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        observeViewModel()
        
        binding.fabAddUser.setOnClickListener {
            showAddUserDialog()
        }
    }

    private fun showAddUserDialog() {
        val dialogBinding = DialogAddUserBinding.inflate(layoutInflater)
        
        // Setup Roles matching Web permissions
        val roles = if (isSuperAdmin) listOf("Employee", "Admin", "SuperAdmin") else listOf("Employee")
        val roleAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, roles)
        dialogBinding.spinnerRole.setAdapter(roleAdapter)
        dialogBinding.spinnerRole.setText("Employee", false)

        // Setup Employees
        lifecycleScope.launch {
            val employeeData = viewModel.availableEmployees.first()
            val employeeNames = employeeData.map { "${it.name} (#${it.employeeId})" }
            val empAdapter = ArrayAdapter(this@UserManagementActivity, android.R.layout.simple_dropdown_item_1line, employeeNames)
            dialogBinding.spinnerEmployee.setAdapter(empAdapter)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Create New User 👤")
            .setView(dialogBinding.root)
            .setPositiveButton("Create") { _, _ ->
                val email = dialogBinding.etUserEmail.text.toString().trim()
                val password = dialogBinding.etUserPassword.text.toString()
                val selectedRole = dialogBinding.spinnerRole.text.toString()
                val empText = dialogBinding.spinnerEmployee.text.toString()
                val employeeId = if (empText.isNotBlank()) empText.substringAfter("(#").substringBefore(")").toIntOrNull() ?: 0 else 0

                if (email.isBlank() || password.isBlank()) {
                    Toast.makeText(this, "Email and Password are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val request = AdminCreateUserRequest(
                    email = email,
                    password = password,
                    role = selectedRole,
                    employeeId = employeeId,
                    displayName = if (employeeId > 0) empText.substringBefore(" (#") else null
                )

                viewModel.createUser(request) { success, message ->
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditRoleDialog(user: UserRepository.UserViewModel) {
        val roles = if (isSuperAdmin) arrayOf("Employee", "Admin") else arrayOf("Employee")
        var selectedIndex = roles.indexOf(user.role).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Role 🛡️")
            .setMessage("Select assigned role for ${user.email}:")
            .setSingleChoiceItems(roles, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Update Role") { _, _ ->
                val newRole = roles[selectedIndex]
                viewModel.changeRole(user.userId, newRole) { success, message ->
                    Toast.makeText(this@UserManagementActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChangePasswordDialog(user: UserRepository.UserViewModel) {
        val inputLayout = TextInputLayout(this).apply {
            setPadding(48, 16, 48, 0)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            hint = "New Password (min 6 chars)"
        }
        val editText = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        inputLayout.addView(editText)

        MaterialAlertDialogBuilder(this)
            .setTitle("Change Password 🔑")
            .setMessage("Enter new password for ${user.email}:")
            .setView(inputLayout)
            .setPositiveButton("Update") { _, _ ->
                val newPass = editText.text?.toString()?.trim() ?: ""
                if (newPass.length < 6) {
                    Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.resetPassword(user.userId, newPass) { success, message ->
                    Toast.makeText(this@UserManagementActivity, message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteUserDialog(user: UserRepository.UserViewModel) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete User ⚠️")
            .setMessage("Are you sure you want to delete user account '${user.email}'? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteUser(user.userId) { success, message ->
                    Toast.makeText(this@UserManagementActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupRecyclerView() {
        binding.rvUsers.layoutManager = LinearLayoutManager(this)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        binding.progressBar.isVisible = loading
                        if (loading) {
                            binding.llEmptyState.isVisible = false
                        } else {
                            binding.llEmptyState.isVisible = viewModel.users.value.isEmpty()
                        }
                    }
                }
                launch {
                    viewModel.users.collectLatest { users ->
                        binding.rvUsers.adapter = UserAdapter(users)
                        binding.llEmptyState.isVisible = users.isEmpty() && !viewModel.isLoading.value
                        binding.rvUsers.isVisible = users.isNotEmpty()
                    }
                }
            }
        }
    }

    inner class UserAdapter(private val users: List<UserRepository.UserViewModel>) : RecyclerView.Adapter<UserAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemUserManagementBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(users[position])
        }

        override fun getItemCount() = users.size

        inner class ViewHolder(val itemBinding: ItemUserManagementBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(user: UserRepository.UserViewModel) {
                val initial = if (user.email.isNotBlank()) user.email.first().uppercaseChar().toString() else "?"
                itemBinding.tvUserAvatar.text = initial
                itemBinding.tvUserEmail.text = user.email

                if (user.isDisabled) {
                    itemBinding.tvUserSuspended.visibility = View.VISIBLE
                } else {
                    itemBinding.tvUserSuspended.visibility = View.GONE
                }

                if (!user.employeeName.isNullOrBlank()) {
                    itemBinding.tvLinkedStaff.text = "👤 ${user.employeeName}"
                    itemBinding.tvLinkedStaff.setTypeface(null, Typeface.NORMAL)
                } else {
                    itemBinding.tvLinkedStaff.text = "Unlinked"
                    itemBinding.tvLinkedStaff.setTypeface(null, Typeface.ITALIC)
                }

                // Role badge styling matching Web
                when (user.role) {
                    "SuperAdmin" -> {
                        itemBinding.tvUserRoleBadge.text = "👑 SuperAdmin"
                        itemBinding.tvUserRoleBadge.setBackgroundResource(R.drawable.bg_rounded_card_outline)
                        itemBinding.tvUserRoleBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF8E1"))
                        itemBinding.tvUserRoleBadge.setTextColor(Color.parseColor("#F57F17"))
                    }
                    "Admin" -> {
                        itemBinding.tvUserRoleBadge.text = "🛡️ Company Admin"
                        itemBinding.tvUserRoleBadge.setBackgroundResource(R.drawable.bg_rounded_card_outline)
                        itemBinding.tvUserRoleBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E1F5FE"))
                        itemBinding.tvUserRoleBadge.setTextColor(Color.parseColor("#0288D1"))
                    }
                    else -> {
                        itemBinding.tvUserRoleBadge.text = "👤 Employee"
                        itemBinding.tvUserRoleBadge.setBackgroundResource(R.drawable.bg_rounded_card_outline)
                        itemBinding.tvUserRoleBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8EAF6"))
                        itemBinding.tvUserRoleBadge.setTextColor(Color.parseColor("#3F51B5"))
                    }
                }

                // Web parity actions:
                // Edit role: user.CurrentRole != "SuperAdmin" && (IsSuperAdmin || user.CurrentRole == "Employee")
                val canEditRole = user.role != "SuperAdmin" && (isSuperAdmin || user.role == "Employee")
                itemBinding.btnEditRole.isVisible = canEditRole
                itemBinding.btnEditRole.setOnClickListener {
                    showEditRoleDialog(user)
                }

                // Password reset: IsSuperAdmin || user.CurrentRole == "Employee"
                val canChangePassword = isSuperAdmin || user.role == "Employee"
                itemBinding.btnChangePassword.isVisible = canChangePassword
                itemBinding.btnChangePassword.setOnClickListener {
                    showChangePasswordDialog(user)
                }

                // Delete: (IsSuperAdmin && user.CurrentRole != "SuperAdmin") || (!IsSuperAdmin && user.CurrentRole == "Employee")
                val canDelete = (isSuperAdmin && user.role != "SuperAdmin") || (!isSuperAdmin && user.role == "Employee")
                itemBinding.btnDeleteUser.isVisible = canDelete
                itemBinding.btnDeleteUser.setOnClickListener {
                    showDeleteUserDialog(user)
                }
            }
        }
    }
}

