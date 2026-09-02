/*
 * Copyright 2025 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.model

import android.os.Parcel
import android.os.Parcelable
import com.kunzisoft.keepass.utils.CharArrayUtil.clear

// ⚠️ 原为 @Parcelize：AGP 9 新 DSL 下 kotlin-parcelize 编译插件不再生效
// （KGP 的 ParcelizeSubplugin 要求 android 扩展仍是旧的 BaseExtension），故手写实现。
data class Passkey(
    val username: String,
    val privateKeyPem: CharArray,
    val credentialId: String,
    val userHandle: String,
    val relyingParty: String,
    val backupEligibility: Boolean?,
    val backupState: Boolean?
): Parcelable {

    constructor(passkey: Passkey) : this(
        passkey.username,
        passkey.privateKeyPem.copyOf(),
        passkey.credentialId,
        passkey.userHandle,
        passkey.relyingParty,
        passkey.backupEligibility,
        passkey.backupState
    )

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString()?.toCharArray() ?: charArrayOf(),
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readInt().toBooleanOrNull(),
        parcel.readInt().toBooleanOrNull()
    )

    fun clear() {
        privateKeyPem.clear()
    }

    // Do not compare BE and BS because are modifiable by the user
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Passkey

        if (username != other.username) return false
        if (!privateKeyPem.contentEquals(other.privateKeyPem)) return false
        if (credentialId != other.credentialId) return false
        if (userHandle != other.userHandle) return false
        if (relyingParty != other.relyingParty) return false

        return true
    }

    override fun hashCode(): Int {
        var result = username.hashCode()
        result = 31 * result + privateKeyPem.contentHashCode()
        result = 31 * result + credentialId.hashCode()
        result = 31 * result + userHandle.hashCode()
        result = 31 * result + relyingParty.hashCode()
        return result
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(username)
        parcel.writeString(privateKeyPem.concatToString())
        parcel.writeString(credentialId)
        parcel.writeString(userHandle)
        parcel.writeString(relyingParty)
        parcel.writeInt(backupEligibility.toParcelInt())
        parcel.writeInt(backupState.toParcelInt())
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Passkey> {
        override fun createFromParcel(parcel: Parcel): Passkey = Passkey(parcel)
        override fun newArray(size: Int): Array<Passkey?> = arrayOfNulls(size)
    }
}

// Parcel 无「可空 Boolean」原语，用 -1 / 0 / 1 三态编码。
private fun Boolean?.toParcelInt(): Int = when (this) {
    true -> 1
    false -> 0
    null -> -1
}

private fun Int.toBooleanOrNull(): Boolean? = when (this) {
    1 -> true
    0 -> false
    else -> null
}
