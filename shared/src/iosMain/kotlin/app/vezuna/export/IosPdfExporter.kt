package app.vezuna.export

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGContextAddCurveToPoint
import platform.CoreGraphics.CGContextAddLineToPoint
import platform.CoreGraphics.CGContextAddQuadCurveToPoint
import platform.CoreGraphics.CGContextBeginPath
import platform.CoreGraphics.CGContextClosePath
import platform.CoreGraphics.CGContextFillPath
import platform.CoreGraphics.CGContextSetLineCap
import platform.CoreGraphics.CGContextSetLineJoin
import platform.CoreGraphics.CGLineCap
import platform.CoreGraphics.CGLineJoin
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
import platform.CoreGraphics.kCGPDFContextCreator
import platform.CoreGraphics.kCGPDFContextTitle
import platform.UIKit.UIGraphicsPDFRenderer
import platform.UIKit.UIGraphicsPDFRendererFormat
import platform.UIKit.UIGraphicsPDFRendererContext
import platform.UIKit.drawAtPoint
import platform.UIKit.sizeWithAttributes

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class NativePdfExporter {
    actual fun export(document: DoctorPdfDocument): ExportedPdf {
        val format = UIGraphicsPDFRendererFormat().apply {
            documentInfo = mapOf<Any?, Any?>(kCGPDFContextTitle to document.title, kCGPDFContextCreator to "Vezuna")
        }
        val renderer = UIGraphicsPDFRenderer(CGRectMake(0.0, 0.0, document.paper.width, document.paper.height), format)
        val data = renderer.PDFDataWithActions { context ->
            PdfReportLayout(IosPdfCanvas(requireNotNull(context), document.paper)).render(document)
        }
        return ExportedPdf(DoctorExportContent.fileName(document), data.toByteArray())
    }

    private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).apply {
        if (isNotEmpty()) usePinned { platform.posix.memcpy(it.addressOf(0), bytes, length) }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosPdfCanvas(private val drawing: UIGraphicsPDFRendererContext, paper: PaperSize) : PdfCanvas {
    override val pageWidth = paper.width
    override val pageHeight = paper.height

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

    override fun path(ops: List<PathOp>, color: PdfColor, strokeWidth: Double?) {
        val context = drawing.CGContext
        CGContextBeginPath(context)
        ops.forEach { op ->
            when (op) {
                is PathOp.MoveTo -> CGContextMoveToPoint(context, op.x, op.y)
                is PathOp.LineTo -> CGContextAddLineToPoint(context, op.x, op.y)
                is PathOp.CubicTo -> CGContextAddCurveToPoint(context, op.x1, op.y1, op.x2, op.y2, op.x, op.y)
                is PathOp.QuadTo -> CGContextAddQuadCurveToPoint(context, op.x1, op.y1, op.x, op.y)
                PathOp.Close -> CGContextClosePath(context)
            }
        }
        if (strokeWidth == null) {
            CGContextSetFillColorWithColor(context, color.ui().CGColor)
            CGContextFillPath(context)
        } else {
            CGContextSetStrokeColorWithColor(context, color.ui().CGColor)
            CGContextSetLineWidth(context, strokeWidth)
            CGContextSetLineCap(context, CGLineCap.kCGLineCapRound)
            CGContextSetLineJoin(context, CGLineJoin.kCGLineJoinRound)
            CGContextStrokePath(context)
            CGContextSetLineCap(context, CGLineCap.kCGLineCapButt)
            CGContextSetLineJoin(context, CGLineJoin.kCGLineJoinMiter)
        }
    }

    /** Noto Sans is registered by the app (UIAppFonts); the system font is only a fallback. */
    private fun font(size: Double, bold: Boolean): UIFont =
        UIFont.fontWithName(if (bold) "NotoSans-Bold" else "NotoSans-Regular", size)
            ?: if (bold) UIFont.boldSystemFontOfSize(size) else UIFont.systemFontOfSize(size)

    private fun attributes(size: Double, color: PdfColor, bold: Boolean): Map<Any?, Any?> = mapOf(
        NSFontAttributeName to font(size, bold),
        NSForegroundColorAttributeName to color.ui(),
    )

    private fun PdfColor.ui() = UIColor.colorWithRed(red, green, blue, 1.0)
}
