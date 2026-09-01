package com.bnm.diagnosis.print

/**
 * Print an [A4Doc] (the non-thermal, full-page invoice) on this platform.
 *  • Desktop → OS print dialog; the ops are replayed with Graphics2D.
 *  • Android → native print preview (PrintManager); ops are rendered into a
 *    PdfDocument first. Needs the ACTIVITY registered via initA4Print(...) in
 *    MainActivity (PrintManager.print refuses a non-activity context).
 *  • iOS → staged (same as the family's PDF printing).
 * Returns a short human-readable status for the operator.
 */
expect fun printA4(doc: A4Doc): String
