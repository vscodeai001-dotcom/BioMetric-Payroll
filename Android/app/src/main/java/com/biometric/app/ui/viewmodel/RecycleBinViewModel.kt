package com.biometric.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometric.app.data.DataSafetyManager
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.RecycleBinItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecycleBinViewModel @Inject constructor(
    private val repository: MainRepository,
    private val dataSafety: DataSafetyManager,
    sharedViewModel: SharedViewModel,
) : ViewModel() {

    private val _filterModule = MutableStateFlow<String?>(null)
    val filterModule: StateFlow<String?> = _filterModule.asStateFlow()

    private val _limit = MutableStateFlow(100)
    private val _canLoadMore = MutableStateFlow(value = true)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _operationMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val operationMessage: SharedFlow<String> = _operationMessage.asSharedFlow()

    val binItems: StateFlow<List<RecycleBinItem>> = combine(
        _limit.flatMapLatest { 
            _isLoading.value = true
            repository.getRecentRecycleBinItems(it)
                .onEach { _isLoading.value = false }
        },
        _filterModule,
        sharedViewModel.selectedShop,
    ) { items, filter, shop ->
        val filtered = items.asSequence().filter { 
            val matchesShop = if ((it.shopId != null) && (shop != null)) it.shopId == shop.shopId else true
            val matchesModule = (filter == null) || (it.module == filter)
            matchesShop && matchesModule
        }.sortedByDescending { it.timestamp }.toList()
        
        _canLoadMore.value = items.size >= _limit.value
        filtered
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun loadMore() {
        _limit.value += 100
    }

    fun setFilter(module: String?) {
        _filterModule.value = module
    }

    fun restoreItem(item: RecycleBinItem) {
        viewModelScope.launch {
            val success = dataSafety.restoreItem(item)
            _operationMessage.emit(if (success) "${item.itemName} restored successfully ♻️" else "Restore failed. No data was removed from the recycle bin.")
        }
    }

    fun permanentlyDeleteItem(item: RecycleBinItem) {
        viewModelScope.launch {
            val success = dataSafety.permanentlyDeleteRecycleBinItem(item)
            _operationMessage.emit(if (success) "Recycle-bin snapshot permanently deleted." else "Permanent deletion failed.")
        }
    }

    fun emptyRecycleBin() {
        viewModelScope.launch {
            val items = binItems.value.toList()
            var deleted = 0
            items.forEach { if (dataSafety.permanentlyDeleteRecycleBinItem(it)) deleted++ }
            _operationMessage.emit("Recycle Bin cleared: $deleted item(s) permanently removed.")
        }
    }
}
