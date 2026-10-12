package dev.danteai.voice.svc;

/** Ícono de 16x16 (ARGB) para la categoría de volumen "Dante" en el menú de Simple Voice Chat: cabeza de labrador negro. */
final class DanteIcon {
	private static final String[] PIXELS = {
		"................",
		"...KK......KK...",
		"..KKKKKKKKKKKK..",
		"..KKKKKKKKKKKK..",
		".KKKKKKKKKKKKKK.",
		".KKKWBKKKKWBKKK.",
		".KKKBBKKKKBBKKK.",
		".KKKKKKKKKKKKKK.",
		".KKKKKGGGGKKKKK.",
		"..KKKGGNNGGKKK..",
		"..KKKGGGGGGKKK..",
		"...KKGGGGGGKK...",
		"....KKKPPKKK....",
		".....RRRRRR.....",
		"......RYYR......",
		"................",
	};

	private DanteIcon() {
	}

	static int[][] create() {
		int[][] icon = new int[16][16];
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				icon[x][y] = switch (PIXELS[y].charAt(x)) {
					case 'K' -> 0xFF1C1C22;
					case 'W' -> 0xFFFFFFFF;
					case 'B' -> 0xFF3A2414;
					case 'G' -> 0xFF2E2E36;
					case 'N' -> 0xFF050505;
					case 'P' -> 0xFFE58A9A;
					case 'R' -> 0xFFC0282D;
					case 'Y' -> 0xFFF2C230;
					default -> 0x00000000;
				};
			}
		}
		return icon;
	}
}
