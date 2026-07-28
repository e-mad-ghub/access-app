package com.example.access.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: Member)

    @Query("SELECT * FROM members WHERE qrCodeHash = :hash LIMIT 1")
    suspend fun getMemberByHash(hash: String): Member?

    @Query("SELECT * FROM members")
    fun getAllMembers(): LiveData<List<Member>>

    @Query("SELECT * FROM members")
    suspend fun getAllMembersList(): List<Member>

    @Query("UPDATE members SET status = :status WHERE memberId = :memberId")
    suspend fun updateStatus(memberId: String, status: String)

    @Query("SELECT COUNT(*) FROM members")
    suspend fun getMemberCount(): Int

    @Query("DELETE FROM members WHERE memberId = :id")
    suspend fun deleteMember(id: String)
}
