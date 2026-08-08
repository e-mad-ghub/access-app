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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<Member>)

    @Query("SELECT EXISTS(SELECT 1 FROM members WHERE memberId = :id)")
    suspend fun memberIdExists(id: String): Boolean

    @Query("DELETE FROM members")
    suspend fun deleteAllMembers()

    /**
     * Replaces the local cache with one authoritative cloud snapshot.
     * The transaction prevents scanners from observing a partially imported list.
     */
    @Transaction
    suspend fun replaceAllMembers(members: List<Member>) {
        deleteAllMembers()
        insertMembers(members)
    }

    @Query("DELETE FROM members WHERE memberId = :id")
    suspend fun deleteMember(id: String)

    @Query("UPDATE members SET status = :status WHERE memberId = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("SELECT * FROM members WHERE qrCodeHash = :hash LIMIT 1")
    suspend fun getMemberByHash(hash: String): Member?
}
