package com.example.access.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "members")
data class Member(
    @PrimaryKey val memberId: String,
    val fullName: String,
    val status: String, 
    val qrCodeHash: String,
    val lastUpdated: String,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null
)
