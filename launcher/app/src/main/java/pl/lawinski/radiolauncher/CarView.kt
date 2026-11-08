package pl.lawinski.radiolauncher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.flow.MutableStateFlow

enum class Door(val label: String) {
    FL("lewe przednie drzwi"),
    FR("prawe przednie drzwi"),
    RL("lewe tylne drzwi"),
    RR("prawe tylne drzwi"),
    TRUNK("klapa"),
    HOOD("maska"),
}

/** Dane z auta. Puste, dopóki nie podłączymy źródła (moduł CAN radia albo OBD). */
data class CarStatus(
    val fuelL: Float? = null,
    val rangeKm: Int? = null,
    val open: Set<Door> = emptySet(),
    /** P, R, N, D, S, M albo null, gdy auto nie podaje biegu. */
    val gear: String? = null,
    /** Niezapięty pas kierowcy. */
    val beltWarn: Boolean = false,
    /** Temperatura zewnętrzna z czujnika auta (krok 0,5 °C). */
    val outsideC: Float? = null,
)

object CarData {
    val status = MutableStateFlow(CarStatus())

    /** Pojemność baku F11 (diesel i benzyna) do paska paliwa. */
    const val TANK_L = 70f
}

/**
 * Auto z góry (przodem do góry) z otwartymi drzwiami i klapą narysowanymi na zdjęciu.
 * Położenia drzwi są ułamkami wymiarów zdjęcia car_f11_top.
 */
@Composable
fun TopDownCar(open: Set<Door>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().aspectRatio(0.5f)) {
        Image(painterResource(R.drawable.car_f11_top), "BMW F11 z góry", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val warn = Warn
            if (Door.HOOD in open) drawArea(0.25f, 0.07f, 0.75f, 0.25f, warn)
            if (Door.TRUNK in open) {
                drawArea(0.27f, 0.77f, 0.73f, 0.925f, warn)
                drawPanel(Offset(size.width * 0.27f, size.height * 0.925f), Size(size.width * 0.46f, size.height * 0.06f), warn)
            }
            door(Door.FL in open, left = true, y0 = 0.33f, y1 = 0.52f, warn)
            door(Door.RL in open, left = true, y0 = 0.53f, y1 = 0.71f, warn)
            door(Door.FR in open, left = false, y0 = 0.33f, y1 = 0.52f, warn)
            door(Door.RR in open, left = false, y0 = 0.53f, y1 = 0.71f, warn)
        }
    }
}

private fun DrawScope.door(isOpen: Boolean, left: Boolean, y0: Float, y1: Float, color: Color) {
    if (!isOpen) return
    val x = size.width * (if (left) 0.215f else 0.785f)
    val hinge = Offset(x, size.height * y0)
    val len = size.height * (y1 - y0)
    val thick = size.width * 0.05f
    drawArea(if (left) 0.215f else 0.70f, y0, if (left) 0.30f else 0.785f, y1, color)
    // drzwi obracają się wokół zawiasu z przodu: lewe w lewo, prawe w prawo
    rotate(if (left) 55f else -55f, pivot = hinge) {
        drawPanel(Offset(hinge.x - thick / 2, hinge.y), Size(thick, len), color)
    }
}

private fun DrawScope.drawArea(x0: Float, y0: Float, x1: Float, y1: Float, color: Color) {
    drawRoundRect(
        color.copy(alpha = 0.28f),
        topLeft = Offset(size.width * x0, size.height * y0),
        size = Size(size.width * (x1 - x0), size.height * (y1 - y0)),
        cornerRadius = CornerRadius(12f, 12f),
    )
}

private fun DrawScope.drawPanel(topLeft: Offset, sz: Size, color: Color) {
    drawRoundRect(Color(0xFFF2F4F7), topLeft, sz, CornerRadius(10f, 10f))
    drawRoundRect(color, topLeft, sz, CornerRadius(10f, 10f), style = Stroke(width = 5f))
}
