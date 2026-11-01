package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity

/** 保留资源清单及落地状态，附件关闭或尚未下载时不能把待落地资源误判为本地删除。 */
@Entity(tableName = "sync_resources", primaryKeys = ["entityType", "entityId"])
data class SyncResourceEntity(
    val entityType: String,
    val entityId: String,
    val payload: String,
    val materializedHash: String = "",
    val deleted: Boolean = false,
    val localHash: String = "",
)
