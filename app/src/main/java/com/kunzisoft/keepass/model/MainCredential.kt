package com.kunzisoft.keepass.model

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

data class MainCredential(var masterPassword: String? = null,
                          var keyFileUri: Uri? = null,
                          var yubikeyResponse: ByteArray? = null) : Parcelable {

    constructor(parcel: Parcel) : this(
            parcel.readString(),
            parcel.readParcelable(Uri::class.java.classLoader),
            parcel.createByteArray()) {
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(masterPassword)
        parcel.writeParcelable(keyFileUri, flags)
        parcel.writeByteArray(yubikeyResponse)
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MainCredential

        if (masterPassword != other.masterPassword) return false
        if (keyFileUri != other.keyFileUri) return false
        if (yubikeyResponse != null) {
            if (other.yubikeyResponse == null) return false
            if (!yubikeyResponse.contentEquals(other.yubikeyResponse)) return false
        } else if (other.yubikeyResponse != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = masterPassword?.hashCode() ?: 0
        result = 31 * result + (keyFileUri?.hashCode() ?: 0)
        result = 31 * result + (yubikeyResponse?.contentHashCode() ?: 0)
        return result
    }

    companion object CREATOR : Parcelable.Creator<MainCredential> {
        override fun createFromParcel(parcel: Parcel): MainCredential {
            return MainCredential(parcel)
        }

        override fun newArray(size: Int): Array<MainCredential?> {
            return arrayOfNulls(size)
        }
    }
}
