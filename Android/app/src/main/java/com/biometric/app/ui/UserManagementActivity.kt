package com.biometric.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.entity.UserRole
import com.biometric.app.data.repository.UserRepository
import com.biometric.app.databinding.ActivityUserManagementBinding
import com.biometric.app.ui.viewmodel.UserViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class UserManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserManagementBinding
    private val viewModel: UserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Web parity: User/Role Management is a SuperAdmin-only governance function.
        val role = getSharedPreferences("auth_prefs", MODE_PRIVATE)
            .getString("user_role", UserRole.STAFF.name)
            ?.trim()
            ?.uppercase()
        if (role != UserRole.SUPER_ADMIN.name) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Access Restricted 🛡️")
                .setMessage("User and Role Management is available only to SuperAdmin.")
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
            // Replicate UserCreationForm from Web
            MaterialAlertDialogBuilder(this)
                .setTitle("Add New User")
                .setMessage("Please use the Web Portal for user creation in Phase 3. Deletion and View are supported here.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun setupRecyclerView() {
        binding.rvUsers.layoutManager = LinearLayoutManager(this)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isLoading.collectLatest { binding.progressBar.isVisible = it }
                }
                launch {
                    viewModel.users.collectLatest { users ->
                        binding.rvUsers.adapter = UserAdapter(users)
                    }
                }
            }
        }
    }

    inner class UserAdapter(private val users: List<UserRepository.UserViewModel>) : RecyclerView.Adapter<UserAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val user = users[position]
            val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvHistoryTitle)
            val tvDate = holder.itemView.findViewById<TextView>(R.id.tvHistoryDate)
            val tvReason = holder.itemView.findViewById<TextView>(R.id.tvHistoryReason)
            val tvIcon = holder.itemView.findViewById<TextView>(R.id.tvHistoryIcon)

            tvTitle.text = "✉️ ${user.email}"
            tvDate.text = "🛡️ Role: ${user.role}"
            tvReason.text = "🔗 Linked: ${user.employeeName ?: "Unlinked"}"
            tvIcon.text = "👤"

            holder.itemView.setOnLongClickListener {
                MaterialAlertDialogBuilder(this@UserManagementActivity)
                    .setTitle("Remove User Profile")
                    .setMessage(
                        "This removes only the Firebase user_profiles record for ${user.email}. " +
                            "It does NOT delete the Firebase Authentication account or Web Identity account. " +
                            "Use the Web Portal for canonical account deletion or role changes."
                    )
                    .setPositiveButton("Remove Profile") { _, _ -> viewModel.deleteUser(user.userId) }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }

        override fun getItemCount() = users.size
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v)
    }
}
