package app.vezuna.export

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGContextAddLineToPoint
import platform.CoreGraphics.CGContextFillEllipseInRect
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextMoveToPoint
import platform.CoreGraphics.CGContextSetFillColorWithColor
import platform.CoreGraphics.CGContextSetLineWidth
import platform.CoreGraphics.CGContextSetStrokeColorWithColor
import platform.CoreGraphics.CGContextStrokePath
import platform.CoreGraphics.CGContextStrokeRect
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIGraphicsPDFRenderer
import platform.UIKit.UIGraphicsPDFRendererContext
import platform.UIKit.drawAtPoint
import platform.UIKit.sizeWithAttributes

private const val PAGE_WIDTH = 612.0
private const val PAGE_HEIGHT = 792.0

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class NativePdfExporter {
    actual fun export(document: DoctorPdfDocument): ExportedPdf {
        val renderer = UIGraphicsPDFRenderer(CGRectMake(0.0, 0.0, PAGE_WIDTH, PAGE_HEIGHT))
        val data = renderer.PDFDataWithActions { context ->
            PdfReportLayout(IosPdfCanvas(requireNotNull(context))).render(document)
        }
        return ExportedPdf("vezuna-doctor-export.pdf", data.toByteArray())
    }

    private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).apply {
        if (isNotEmpty()) usePinned { platform.posix.memcpy(it.addressOf(0), bytes, length) }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosPdfCanvas(private val drawing: UIGraphicsPDFRendererContext) : PdfCanvas {
    override val pageWidth = PAGE_WIDTH
    override val pageHeight = PAGE_HEIGHT

    override fun newPage() = drawing.beginPage()

    override fun measure(text: String, size: Double, bold: Boolean): Double =
        NSString.create(string = text).sizeWithAttributes(attributes(size, PdfColor(0.0, 0.0, 0.0), bold)).useContents { width }

    override fun text(text: String, x: Double, top: Double, size: Double, color: PdfColor, bold: Boolean) {
        NSString.create(string = text).drawAtPoint(CGPointMake(x, top), attributes(size, color, bold))
    }

    override fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: PdfColor, width: Double) {
        CGContextSetStrokeColorWithColor(drawing.CGContext, color.ui().CGColor)
        CGContextSetLineWidth(drawing.CGContext, width)
        CGContextMoveToPoint(drawing.CGContext, x1, y1)
        CGContextAddLineToPoint(drawing.CGContext, x2, y2)
        CGContextStrokePath(drawing.CGContext)
    }

    override fun rect(x: Double, top: Double, width: Double, height: Double, color: PdfColor) {
        CGContextSetFillColorWithColor(drawing.CGContext, color.ui().CGColor)
        CGContextFillRect(drawing.CGContext, CGRectMake(x, top, width, height))
    }

    override fun strokeRect(x: Double, top: Double, width: Double, height: Double, color: PdfColor, lineWidth: Double) {
        CGContextSetStrokeColorWithColor(drawing.CGContext, color.ui().CGColor)
        CGContextSetLineWidth(drawing.CGContext, lineWidth)
        CGContextStrokeRect(drawing.CGContext, CGRectMake(x, top, width, height))
    }

    override fun circle(x: Double, y: Double, radius: Double, color: PdfColor) {
        CGContextSetFillColorWithColor(drawing.CGContext, color.ui().CGColor)
        CGContextFillEllipseInRect(drawing.CGContext, CGRectMake(x - radius, y - radius, radius * 2, radius * 2))
    }

    private fun attributes(size: Double, color: PdfColor, bold: Boolean): Map<Any?, Any?> = mapOf(
        NSFontAttributeName to if (bold) UIFont.boldSystemFontOfSize(size) else UIFont.systemFontOfSize(size),
        NSForegroundColorAttributeName to color.ui(),
    )

    private fun PdfColor.ui() = UIColor.colorWithRed(red, green, blue, 1.0)
}
