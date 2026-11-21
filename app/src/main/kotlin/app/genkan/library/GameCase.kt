package app.genkan.library

/** The kind of box a platform's games were sold in, for drawing them on the shelf. */
enum class CaseStyle {
    /** A plastic DVD-style case: the PS2, the GameCube, the Wii, the PSP's taller one. */
    KEEP_CASE,
    /** A CD jewel case with its hinge on the left: the PlayStation, the Saturn, the Dreamcast. */
    JEWEL_CASE,
    /** The DS and 3DS cases: small, rounded, a little taller than wide. */
    CARD_CASE,
    /** A printed cardboard box with square corners: the Famicom, the Super Famicom, the Game Boys. */
    CARDBOARD_BOX,
}

/**
 * How a platform's games are drawn on the shelf: the case's [style], the front cover's width over
 * its height when no cover has loaded yet ([aspect]), and the colour of a blank case ([color],
 * ARGB). The real cover's proportions replace [aspect] once it loads; see [GameCase.aspectFor].
 */
data class CaseSpec(val style: CaseStyle, val aspect: Float, val color: Long)

/** The case each platform's games are drawn in, and the arithmetic that sizes them. */
object GameCase {

    /** A plastic case of no particular console, for platforms not listed. */
    val DEFAULT = CaseSpec(CaseStyle.KEEP_CASE, 0.71f, 0xFF2E333D)

    /**
     * RomM's platform slugs, by case. The aspect ratios are the printed fronts' (a PS2 cover is
     * 130x184 mm, a PSP one 100x170 mm, a jewel case's insert 120x120 mm); the Japanese boxes of the
     * Famicom and Super Famicom lie on their side, so theirs are wider than tall.
     */
    private val BY_SLUG: Map<String, CaseSpec> = buildMap {
        fun put(spec: CaseSpec, vararg slugs: String) = slugs.forEach { put(it, spec) }
        put(CaseSpec(CaseStyle.JEWEL_CASE, 1.0f, 0xFF1E2128), "psx", "ps1", "saturn", "dc", "dreamcast", "segacd", "pc-fx", "3do", "turbografx-cd", "neo-geo-cd")
        put(CaseSpec(CaseStyle.KEEP_CASE, 0.71f, 0xFF131A2B), "ps2")
        put(CaseSpec(CaseStyle.KEEP_CASE, 0.71f, 0xFF1B1B1F), "ps3", "xbox", "xbox360")
        put(CaseSpec(CaseStyle.KEEP_CASE, 0.71f, 0xFF4B4F8C), "ngc", "gc")
        put(CaseSpec(CaseStyle.KEEP_CASE, 0.71f, 0xFFE4E6EA), "wii", "wiiu")
        put(CaseSpec(CaseStyle.KEEP_CASE, 0.59f, 0xFF1C1C21), "psp")
        put(CaseSpec(CaseStyle.KEEP_CASE, 0.72f, 0xFF1A1A1A), "genesis", "megadrive", "md", "sms")
        put(CaseSpec(CaseStyle.CARD_CASE, 0.89f, 0xFFDDE0E5), "nds")
        put(CaseSpec(CaseStyle.CARD_CASE, 0.89f, 0xFFF0F0F2), "3ds", "n3ds", "new-nintendo-3ds")
        put(CaseSpec(CaseStyle.CARD_CASE, 0.82f, 0xFF2A5DB0), "psvita")
        put(CaseSpec(CaseStyle.CARD_CASE, 0.62f, 0xFFC8202E), "switch")
        put(CaseSpec(CaseStyle.CARDBOARD_BOX, 1.78f, 0xFFD9D3C4), "sfc", "sfam")
        put(CaseSpec(CaseStyle.CARDBOARD_BOX, 1.40f, 0xFFD9D3C4), "snes", "n64")
        put(CaseSpec(CaseStyle.CARDBOARD_BOX, 1.42f, 0xFFB52A32), "famicom", "fc", "fds")
        put(CaseSpec(CaseStyle.CARDBOARD_BOX, 0.73f, 0xFF2B2B2E), "nes")
        put(CaseSpec(CaseStyle.CARDBOARD_BOX, 0.86f, 0xFF6C6F78), "gb", "gbc")
        put(CaseSpec(CaseStyle.CARDBOARD_BOX, 0.86f, 0xFF4A3F8C), "gba")
    }

    /** The case for a RomM platform slug, case-insensitively; [DEFAULT] when it isn't listed. */
    fun specFor(slug: String?): CaseSpec = slug?.let { BY_SLUG[it.lowercase()] } ?: DEFAULT

    /** How far a real cover may stray from its console's proportions before it is reined in. */
    const val ASPECT_TOLERANCE = 1.35f

    /**
     * The front's width over height for a game whose cover measured [realAspect] (null before it
     * loads, or with no cover). A cover far from its console's shape (a scan with the spine and
     * back, a screenshot standing in for art) is pulled back within [ASPECT_TOLERANCE] of
     * [spec]'s aspect, so one odd image can't make a case three times wider than its neighbours.
     */
    fun aspectFor(spec: CaseSpec, realAspect: Float?): Float {
        if (realAspect == null || !realAspect.isFinite() || realAspect <= 0f) return spec.aspect
        return realAspect.coerceIn(spec.aspect / ASPECT_TOLERANCE, spec.aspect * ASPECT_TOLERANCE)
    }

    /**
     * The largest case of [aspect] (width over height) that fits [maxWidth] by [maxHeight], as
     * width to height. Tall cases fill the height; the wide Super Famicom boxes fill the width.
     */
    fun fit(aspect: Float, maxWidth: Float, maxHeight: Float): Pair<Float, Float> {
        val height = minOf(maxHeight, maxWidth / aspect)
        return height * aspect to height
    }
}
