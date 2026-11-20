import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Genera el catalogo de cosmeticos que sirve el backend.
 *
 * Entrada: la carpeta `assets/polyplus` del jar del mod (cosmetics + textures/cosmetics + capes).
 * Salida: `social/assets/cosmetics` con los modelos, una textura por paleta y un catalog.json.
 *
 * Uso:
 *   javac -d <tmp> GenerateWardrobeAssets.java
 *   java -cp <tmp> GenerateWardrobeAssets "C:/.../polyplus_26_3_clean/assets/polyplus" "C:/.../social/assets/cosmetics"
 */
public final class GenerateWardrobeAssets {

    private record Base(String slug, String name, String type, String slot, String source, boolean cape) {}

    /** `targetHue` nulo = dejar el arte original; si no, se gira el tono dominante hasta ahi. */
    private record Palette(String name, String slug, Float targetHue, float saturation, float brightness) {}

    /** Modelos 3D que ya vienen en el jar (slot al que pertenecen). */
    private static final Base[] BASES = {
        new Base("halo_angel", "Angel Halo", "hat", "hat", "textures/cosmetics/halo_angel.png", false),
        new Base("halo_cyber", "Cyber Halo", "hat", "hat", "textures/cosmetics/halo_cyber.png", false),
        new Base("halo_demon", "Demon Halo", "hat", "hat", "textures/cosmetics/halo_demon.png", false),
        new Base("halo_sakura", "Sakura Halo", "hat", "hat", "textures/cosmetics/halo_sakura.png", false),
        new Base("halo_void", "Void Crown", "hat", "hat", "textures/cosmetics/halo_void.png", false),
        new Base("nebula_halo", "Nebula Halo", "hat", "hat", "textures/cosmetics/nebula_halo.png", false),
        new Base("santa_hat", "Santa Hat", "hat", "hat", "textures/cosmetics/santa_hat.png", false),
        new Base("reindeer_antlers", "Reindeer Antlers", "hat", "hat", "textures/cosmetics/reindeer_antlers.png", false),
        new Base("wings_valkyrie", "Valkyrie Wings", "wings", "wings", "textures/cosmetics/wings_valkyrie.png", false),
        new Base("wings_dragon", "Dragon Wings", "wings", "wings", "textures/cosmetics/wings_dragon.png", false),
        new Base("wings_cyber", "Cyber Wings", "wings", "wings", "textures/cosmetics/wings_cyber.png", false),
        new Base("wings_snowflake", "Snowflake Wings", "wings", "wings", "textures/cosmetics/wings_snowflake.png", false),
        new Base("nebula_wings", "Nebula Wings", "wings", "wings", "textures/cosmetics/nebula_wings.png", false),
        new Base("shoulder_elf", "Elf Companion", "shoulder", "shoulder", "textures/cosmetics/shoulder_elf.png", false),
        new Base("shoulder_reindeer", "Reindeer Companion", "shoulder", "shoulder", "textures/cosmetics/shoulder_reindeer.png", false),
        new Base("shoulder_snowman", "Snowman Companion", "shoulder", "shoulder", "textures/cosmetics/shoulder_snowman.png", false),
        new Base("holiday_scarf", "Holiday Scarf", "shoulder", "shoulder", "textures/cosmetics/holiday_scarf.png", false),
        new Base("pet_giftbox", "Giftbox Pet", "pet", "pet", "textures/cosmetics/pet_giftbox.png", false),
        new Base("pet_reindeer", "Baby Reindeer", "pet", "pet", "textures/cosmetics/pet_reindeer.png", false),
        new Base("cape_amethyst", "Amethyst Cape", "cape", "cape", "capes/nebula_amethyst.png", true),
        new Base("cape_galaxy", "Galaxy Cape", "cape", "cape", "capes/nebula_galaxy.png", true),
        new Base("cape_midnight", "Midnight Cape", "cape", "cape", "capes/nebula_midnight.png", true),
        new Base("cape_nova", "Nova Cape", "cape", "cape", "capes/nebula_nova.png", true),
    };

    /** Paletas: el mismo arte con otra dominante de color. */
    private static final Palette[] PALETTES = {
        new Palette("Prism", "prism", null, 1.00f, 1.00f),
        new Palette("Amethyst", "amethyst", 275f, 1.25f, 1.05f),
        new Palette("Aurora", "aurora", 165f, 1.05f, 1.00f),
        new Palette("Solar", "solar", 45f, 1.15f, 1.10f),
        new Palette("Crimson", "crimson", 350f, 1.20f, 1.00f),
        new Palette("Frost", "frost", 195f, 0.75f, 1.15f),
        new Palette("Emerald", "emerald", 140f, 1.15f, 1.00f),
        new Palette("Rose", "rose", 330f, 1.30f, 1.05f),
        new Palette("Cyber", "cyber", 300f, 1.45f, 1.10f),
        new Palette("Obsidian", "obsidian", 260f, 0.35f, 0.55f),
        new Palette("Celestial", "celestial", 50f, 0.20f, 1.30f),
        new Palette("Toxic", "toxic", 85f, 1.40f, 1.15f),
        new Palette("Twilight", "twilight", 250f, 0.95f, 0.85f),
        new Palette("Inferno", "inferno", 20f, 1.30f, 1.05f),
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("uso: GenerateWardrobeAssets <assets/polyplus> <salida>");
            System.exit(2);
        }
        Path source = Path.of(args[0]);
        Path target = Path.of(args[1]);

        Path models = target.resolve("models");
        Path textures = target.resolve("textures");
        Files.createDirectories(models);
        Files.createDirectories(textures);

        List<String> entries = new ArrayList<>();
        int written = 0;

        for (Base base : BASES) {
            Path sourceTexture = source.resolve(base.source());
            if (!Files.isRegularFile(sourceTexture)) {
                System.err.println("falta la textura " + sourceTexture);
                continue;
            }
            BufferedImage art = ImageIO.read(sourceTexture.toFile());
            if (art == null) {
                System.err.println("textura ilegible " + sourceTexture);
                continue;
            }

            String modelPath = null;
            String animationPath = null;
            if (!base.cape()) {
                Path geo = source.resolve("cosmetics/" + base.slug() + ".geo.json");
                Path animation = source.resolve("cosmetics/" + base.slug() + ".animation.json");
                if (!Files.isRegularFile(geo)) {
                    System.err.println("falta el modelo " + geo);
                    continue;
                }
                Files.copy(geo, models.resolve(base.slug() + ".geo.json"), StandardCopyOption.REPLACE_EXISTING);
                modelPath = "models/" + base.slug() + ".geo.json";
                if (Files.isRegularFile(animation)) {
                    Files.copy(animation, models.resolve(base.slug() + ".animation.json"), StandardCopyOption.REPLACE_EXISTING);
                    animationPath = "models/" + base.slug() + ".animation.json";
                }
            }

            for (Palette palette : PALETTES) {
                String slug = base.slug() + "-" + palette.slug();
                String texturePath = "textures/" + slug + ".png";
                BufferedImage tinted = recolor(art, palette);
                ImageIO.write(tinted, "png", textures.resolve(slug + ".png").toFile());
                written++;

                entries.add(entryJson(
                    slug,
                    base,
                    palette,
                    texturePath,
                    modelPath,
                    animationPath));
            }
        }

        Path catalog = target.resolve("catalog.json");
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"items\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            json.append("    ").append(entries.get(i));
            json.append(i + 1 < entries.size() ? ",\n" : "\n");
        }
        json.append("  ]\n}\n");
        Files.writeString(catalog, json.toString(), StandardCharsets.UTF_8);

        System.out.println("texturas: " + written);
        System.out.println("cosmeticos: " + entries.size());
        System.out.println("catalogo: " + catalog.toAbsolutePath());
    }

    private static String entryJson(
        String slug, Base base, Palette palette, String texture, String model, String animation) {
        StringBuilder out = new StringBuilder();
        out.append('{');
        out.append("\"slug\":\"").append(slug).append("\",");
        out.append("\"base\":\"").append(base.slug()).append("\",");
        out.append("\"name\":\"").append(escape(base.name() + " (" + palette.name() + ")")).append("\",");
        out.append("\"type\":\"").append(base.type()).append("\",");
        out.append("\"slots\":[\"").append(base.slot()).append("\"],");
        out.append("\"model\":").append(model == null ? "null" : "\"" + model + "\"").append(',');
        out.append("\"animation\":").append(animation == null ? "null" : "\"" + animation + "\"").append(',');
        out.append("\"texture\":\"").append(texture).append("\"");
        out.append('}');
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Rota el tono y ajusta saturacion/brillo sin tocar el alfa ni los pixeles transparentes. */
    private static BufferedImage recolor(BufferedImage source, Palette palette) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        float shift = palette.targetHue() == null ? 0f : palette.targetHue() - dominantHue(source);
        float[] hsb = new float[3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = source.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    out.setRGB(x, y, 0);
                    continue;
                }
                Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, hsb);
                float hue = ((hsb[0] * 360f + shift) % 360f + 360f) % 360f;
                float saturation = clamp(hsb[1] * palette.saturation());
                float brightness = clamp(hsb[2] * palette.brightness());
                int rgb = Color.HSBtoRGB(hue / 360f, saturation, brightness);
                out.setRGB(x, y, (alpha << 24) | (rgb & 0x00FFFFFF));
            }
        }
        return out;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * Tono medio (circular) del arte, ponderado por saturacion: es el color que domina la
     * textura, asi "Crimson" sale rojo en todos los modelos y no solo en los que ya eran rojos.
     */
    private static float dominantHue(BufferedImage image) {
        double cos = 0, sin = 0;
        float[] hsb = new float[3];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) == 0) continue;
                Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, hsb);
                if (hsb[1] < 0.12f) continue;
                double angle = Math.toRadians(hsb[0] * 360.0);
                double weight = hsb[1] * Math.max(hsb[2], 0.2f);
                cos += Math.cos(angle) * weight;
                sin += Math.sin(angle) * weight;
            }
        }
        if (cos == 0 && sin == 0) return 0f;
        double degrees = Math.toDegrees(Math.atan2(sin, cos));
        return (float) ((degrees + 360.0) % 360.0);
    }

    private GenerateWardrobeAssets() {}
}
