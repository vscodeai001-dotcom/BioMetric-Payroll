package com.biometric.app.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.biometric.app.R
import com.biometric.app.data.entity.WorkShift
import com.biometric.app.databinding.ActivityShiftManagerBinding
import com.biometric.app.databinding.DialogAddShiftBinding
import com.biometric.app.ui.adapter.ShiftAdapter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import dagger.hilt.android.AndroidEntryPoint
import java.util.*

@AndroidEntryPoint
class ShiftManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShiftManagerBinding
    private val auth = FirebaseAuth.getInstance()
    private val dbRef by lazy { 
        FirebaseDatabase.getInstance().getReference("owners")
            .child(auth.currentUser?.uid ?: "unknown").child("shifts")
    }

    private lateinit var adapter: ShiftAdapter
    private val shiftList = mutableListOf<WorkShift>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShiftManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()

        binding.fabAdd.setOnClickListener {
            showAddShiftDialog()
        }

        listenToShifts()
    }

    private fun setupRecyclerView() {
        adapter = ShiftAdapter(shiftList, { _: WorkShift ->
            // Edit flow could be implemented here
        }, { s: WorkShift ->
            deleteShift(s)
        })
        binding.rvShifts.layoutManager = LinearLayoutManager(this)
        binding.rvShifts.adapter = adapter
    }

    private fun listenToShifts() {
        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    shiftList.clear()
                    for (child in snapshot.children) {
                        try {
                            child.getValue(WorkShift::class.java)?.let { shiftList.add(it) }
                        } catch (e: Exception) {
                            Log.e("Shifts", "Failed to parse shift", e)
                        }
                    }
                    adapter.notifyDataSetChanged()
                } catch (e: Exception) {
                    Log.e("Shifts", "Error loading shifts", e)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@ShiftManagerActivity, error.message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showAddShiftDialog() {
        val dialogBinding = DialogAddShiftBinding.inflate(LayoutInflater.from(this))

        AlertDialog.Builder(this)
            .setTitle("Add New Shift 🕒")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val name = dialogBinding.etName.text.toString()
                val start = dialogBinding.etStart.text.toString()
                val end = dialogBinding.etEnd.text.toString()
                val grace = dialogBinding.etGrace.text.toString().toIntOrNull() ?: 15

                if (name.isNotEmpty()) {
                    val id = UUID.randomUUID().toString()
                    val newShift = WorkShift(id, name, start, end, grace)
                    dbRef.child(id).setValue(newShift)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteShift(shift: WorkShift) {
        AlertDialog.Builder(this)
            .setTitle("Delete Shift?")
            .setMessage("Are you sure you want to remove ${shift.name}?")
            .setPositiveButton("Delete") { _, _ ->
                dbRef.child(shift.id).removeValue()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
