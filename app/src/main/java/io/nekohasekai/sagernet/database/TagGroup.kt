package io.nekohasekai.sagernet.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** A classification container, never a copy of a root [ProxyGroup]. */
@Entity(tableName = "tag_groups")
data class TagGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val userOrder: Long = 0L,
) {
    @Dao
    interface Queries {
        @Query("SELECT * FROM tag_groups ORDER BY userOrder, id")
        fun all(): List<TagGroup>

        @Query("SELECT * FROM tag_groups WHERE id = :id")
        fun get(id: Long): TagGroup?

        // Deliberately not a UNIQUE index: undo must preserve two distinct entities
        // when a new same-named tag was created after deletion. Ordinary writes
        // enforce trimmed-name uniqueness in the store's transaction instead.
        @Query("SELECT COUNT(*) FROM tag_groups WHERE name = :name AND id != :exceptId")
        fun nameCount(name: String, exceptId: Long): Int

        @Query("SELECT MAX(userOrder) FROM tag_groups")
        fun maxOrder(): Long?

        @Query("DELETE FROM tag_groups")
        fun reset()

        @Insert(onConflict = OnConflictStrategy.ABORT)
        fun insert(tag: TagGroup): Long

        @Insert(onConflict = OnConflictStrategy.ABORT)
        fun insertAll(tags: List<TagGroup>)

        @Query("UPDATE tag_groups SET name = :name WHERE id = :id")
        fun rename(id: Long, name: String): Int

        @Query("UPDATE tag_groups SET userOrder = :order WHERE id = :id")
        fun reorder(id: Long, order: Long): Int

        @Query("DELETE FROM tag_groups WHERE id = :id")
        fun delete(id: Long): Int
    }
}

@Entity(
    tableName = "tag_memberships",
    primaryKeys = ["tagId", "groupId"],
    foreignKeys = [
        ForeignKey(
            entity = TagGroup::class,
            parentColumns = ["id"], childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProxyGroup::class,
            parentColumns = ["id"], childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["groupId"])],
)
data class TagMembership(val tagId: Long, val groupId: Long) {
    @Dao
    interface Queries {
        @Query("SELECT groupId FROM tag_memberships WHERE tagId = :tagId ORDER BY groupId")
        fun members(tagId: Long): List<Long>

        @Query("SELECT * FROM tag_memberships WHERE groupId = :groupId ORDER BY tagId")
        fun byGroup(groupId: Long): List<TagMembership>

        @Insert(onConflict = OnConflictStrategy.ABORT)
        fun insert(membership: TagMembership)

        @Query("DELETE FROM tag_memberships WHERE tagId = :tagId AND groupId = :groupId")
        fun delete(tagId: Long, groupId: Long): Int

        @Query("DELETE FROM tag_memberships")
        fun reset()

        @Insert(onConflict = OnConflictStrategy.ABORT)
        fun insertAll(memberships: List<TagMembership>)
    }
}
