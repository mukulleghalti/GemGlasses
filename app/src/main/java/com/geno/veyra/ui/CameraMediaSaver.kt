package com.geno.veyra.ui

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraMediaSaver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun savePhoto(
        jpegBytes: ByteArray,
    ): Result<String> {

        if (jpegBytes.isEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    "JPEG data is empty"
                )
            )
        }

        val resolver =
            context.contentResolver

        val fileName =
            "Veyra_${System.currentTimeMillis()}.jpg"

        val values =
            ContentValues().apply {

                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    fileName,
                )

                put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg",
                )

                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/Veyra",
                )

                put(
                    MediaStore.Images.Media.IS_PENDING,
                    1,
                )
            }

        val uri =
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            )
                ?: return Result.failure(
                    IOException(
                        "Could not create Gallery entry"
                    )
                )

        return try {

            resolver.openOutputStream(uri)?.use { output ->
                output.write(jpegBytes)
                output.flush()
            }
                ?: throw IOException(
                    "Could not open Gallery output stream"
                )

            val completedValues =
                ContentValues().apply {
                    put(
                        MediaStore.Images.Media.IS_PENDING,
                        0,
                    )
                }

            resolver.update(
                uri,
                completedValues,
                null,
                null,
            )

            Result.success(
                fileName
            )

        } catch (e: Exception) {

            resolver.delete(
                uri,
                null,
                null,
            )

            Result.failure(e)
        }
    }
}
