package com.biometric.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometric.app.api.AdminCreateUserRequest
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _users = MutableStateFlow<List<UserRepository.UserViewModel>>(emptyList())
    val users = _users.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    val availableEmployees: Flow<List<Employee>> = userRepository.observeEmployees()

    init {
        loadUsers()
    }

    fun loadUsers() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _users.value = userRepository.getAllUsers()
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteUser(userId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                userRepository.deleteUser(userId)
                loadUsers()
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createUser(request: AdminCreateUserRequest, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                userRepository.createUser(request)
                loadUsers()
                onResult(true, "User created successfully ✅")
            } catch (e: Exception) {
                onResult(false, e.message ?: "User creation failed ⚠️")
            } finally {
                _isLoading.value = false
            }
        }
    }
}
