/**
 * Created by J-Jamet on 13/04/2026.
 * Copyright (c) 2026 Jeremy Jamet / Kunzisoft. All rights reserved.
 *
 * This file is part of KeePassDX.
 *
 * KeePassDX is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * KeePassDX is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with KeePassDX. If not, see <https://www.gnu.org/licenses/>.
 */
package com.kunzisoft.keepass.database.element

import android.os.Parcel
import android.os.Parcelable

// ⚠️ 原为 @Parcelize：AGP 9 新 DSL 下 kotlin-parcelize 编译插件不再生效
// （KGP 的 ParcelizeSubplugin 要求 android 扩展仍是旧的 BaseExtension），故手写实现。
data class Tag(
    val name: String
): Parcelable {

    constructor(parcel: Parcel) : this(parcel.readString() ?: "")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Tag

        return name == other.name
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Tag> {
        override fun createFromParcel(parcel: Parcel): Tag = Tag(parcel)
        override fun newArray(size: Int): Array<Tag?> = arrayOfNulls(size)
    }
}