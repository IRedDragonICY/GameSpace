import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon

fun getAppIcon(context: Context, packageName: String): Icon {
    val pm = context.packageManager
    val drawable = pm.getApplicationIcon(packageName)
    val bitmap = Bitmap.createBitmap(
        drawable.intrinsicWidth.takeIf { it > 0 } ?: 144,
        drawable.intrinsicHeight.takeIf { it > 0 } ?: 144,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return Icon.createWithAdaptiveBitmap(bitmap)
}
