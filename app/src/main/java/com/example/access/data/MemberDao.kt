package com.example.access.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface MemberDao {
    @Query("SELECT * FROM members ORDER BY fullName ASC")
    fun getAllMembers(): LiveData<List<Member>>

    @Query("SELECT * FROM members")
    fun getAllMembersList(): List<Member>

    @Query("SELECT COUNT(*) FROM members")
    fun getMemberCount(): LiveData<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: Member)

    @Query("DELETE FROM members WHERE memberId = :id")
    suspend fun deleteMember(id: String)

    @Query("UPDATE members SET status = :status WHERE memberId = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("SELECT * FROM members WHERE qrCodeHash = :hash LIMIT 1")
    suspend fun getMemberByHash(hash: String): Member?
}
