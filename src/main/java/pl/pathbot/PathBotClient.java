package pl.pathbot;

import com.google.gson.Gson;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ucieczka Bot - nagrywa trase i odtwarza ja w petli.
 *
 * WERSJA PRECYZYJNA (1.1.0). Zmiany wzgledem 1.0.0:
 *  1. Odtwarzanie ze sprzezeniem zwrotnym: kazdego ticka porownujemy pozycje gracza
 *     z nagrana i lekko korygujemy kierunek ruchu (yaw), zamiast slepo powtarzac klawisze.
 *  2. Synchronizacja fazy: gdy gracz jest do tylu / do przodu wzgledem nagrania
 *     (lag, kolizje), indeks klatki jest przesuwany w lokalnym oknie.
 *  3. Dokladne dojscie na start: hamowanie, skradanie przy koncowce, czekanie az gracz sie
 *     zatrzyma (predkosc ~0) i dopasowanie yaw/pitch do pierwszej klatki.
 *  4. Resynchronizacja najpierw lokalna (okno +-40 klatek), globalna dopiero w ostatecznosci.
 */
public class PathBotClient implements ClientModInitializer {

    enum State { IDLE, RECORDING, APPROACH, REPLAY }

    // flagi klawiszy zapisane w klatce
    private static final int FWD = 1, BACK = 2, LEFT = 4, RIGHT = 8, JUMP = 16,
            SPRINT = 32, SNEAK = 64, ATTACK = 128, USE = 256;

    // ===== PARAMETRY PRECYZJI (mozna stroic) =====
    /** Tolerancja poziomej odleglosci od startu (bylo 0.35). */
    private static final double START_REACH = 0.10;
    /** Maks. predkosc pozioma (bloki/tick) przy rozpoczeciu nagrania - gracz ma stac. */
    private static final double START_SPEED = 0.02;
    /** Ponizej tej odleglosci od startu bot sie skrada (drobne kroki). */
    private static final double SNEAK_RANGE = 0.8;
    /** Po tylu tickach dojscia akceptujemy luzniejsze warunki startu. */
    private static final int APPROACH_RELAX_TICKS = 80;
    private static final double RELAXED_REACH = 0.30;

    private static final double RESYNC_DIST = 1.5;
    private static final double LOST_DIST = 4.0;
    private static final int STUCK_LIMIT = 100;

    /** Wzmocnienie korekcji kierunku (0..1). Wieksze = ostrzejsze trzymanie trasy. */
    private static final double STEER_GAIN = 0.6;
    /** Maksymalna korekcja yaw w stopniach. */
    private static final double STEER_MAX_DEG = 25.0;
    /** Blad pozycji (bloki) ponizej ktorego nie korygujemy. */
    private static final double STEER_MIN_ERR = 0.02;

    /** Okno lokalnej synchronizacji fazy (klatki wstecz / do przodu). */
    private static final int SYNC_BACK = 4, SYNC_FWD = 12;
    /** O ile blizej (bloki) musi byc inna klatka, zeby przesunac indeks. */
    private static final double SYNC_MARGIN = 0.15;

    private final Path file = FabricLoader.getInstance().getConfigDir().resolve("pathbot.json");
    private final Gson gson = new Gson();

    private KeyBinding recordKey, playKey, showKey;

    private State state = State.IDLE;
    /** Klatka: {x, y, z, yaw, pitch, flagi, slot}. */
    private List<double[]> frames = new ArrayList<>();
    private int idx = 0;
    private int stuck = 0;
    private int approachTicks = 0;
    private Vec3d lastPos = Vec3d.ZERO;
    private int loops = 0;
    private int lastFlags = 0;
    private boolean showPath = true;
    private int particleTick = 0;

    @Override
    public void onInitializeClient() {
        recordKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.pathbot.record", InputUtil.Type.KEYSYM, 82, "category.pathbot")); // R
        playKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.pathbot.play", InputUtil.Type.KEYSYM, 80, "category.pathbot"));   // P
        showKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.pathbot.show", InputUtil.Type.KEYSYM, 86, "category.pathbot"));   // V

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("botucieczka")
                        .executes(ctx -> { sendInfo(ctx.getSource()); return 1; })
                        .then(ClientCommandManager.literal("info")
                                .executes(ctx -> { sendInfo(ctx.getSource()); return 1; }))));

        load();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    // ------------------------------------------------------------------ tick

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            state = State.IDLE;
            return;
        }

        while (recordKey.wasPressed()) toggleRecording(mc);
        while (playKey.wasPressed()) togglePlaying(mc);
        while (showKey.wasPressed()) {
            showPath = !showPath;
            msg(p, showPath ? "Podglad trasy: wlaczony" : "Podglad trasy: wylaczony");
        }

        switch (state) {
            case RECORDING -> record(mc, p);
            case APPROACH -> approach(mc, p);
            case REPLAY -> replay(mc, p);
            default -> { }
        }
        drawPath(mc, p);
    }

    // ------------------------------------------------------------- recording

    private void toggleRecording(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (state == State.RECORDING) {
            state = State.IDLE;
            save();
            msg(p, "Zapisano " + frames.size() + " klatek (" + (frames.size() / 20) + " s).");
        } else {
            stopBot(mc);
            frames = new ArrayList<>();
            state = State.RECORDING;
            msg(p, "Nagrywam... (R aby zakonczyc). Zakoncz blisko miejsca startu, zeby petla byla plynna.");
        }
    }

    private void record(MinecraftClient mc, ClientPlayerEntity p) {
        GameOptions o = mc.options;
        int f = 0;
        if (o.forwardKey.isPressed()) f |= FWD;
        if (o.backKey.isPressed()) f |= BACK;
        if (o.leftKey.isPressed()) f |= LEFT;
        if (o.rightKey.isPressed()) f |= RIGHT;
        if (o.jumpKey.isPressed()) f |= JUMP;
        if (p.isSprinting()) f |= SPRINT;
        if (o.sneakKey.isPressed()) f |= SNEAK;
        if (o.attackKey.isPressed()) f |= ATTACK;
        if (o.useKey.isPressed()) f |= USE;

        frames.add(new double[]{
                p.getX(), p.getY(), p.getZ(),
                p.getYaw(), p.getPitch(),
                f,
                p.getInventory().selectedSlot
        });
    }

    // --------------------------------------------------------------- playing

    private void togglePlaying(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (state == State.APPROACH || state == State.REPLAY) {
            stopBot(mc);
            msg(p, "Bot zatrzymany. Przejsc petli: " + loops);
            return;
        }
        if (state == State.RECORDING) {
            state = State.IDLE;
            save();
        }
        if (frames.size() < 5) {
            msg(p, "Brak nagranej trasy (nagraj ja klawiszem R).");
            return;
        }
        loops = 0;
        beginApproach(p);
        msg(p, "Bot rusza (P aby zatrzymac).");
    }

    private void beginApproach(ClientPlayerEntity p) {
        state = State.APPROACH;
        stuck = 0;
        approachTicks = 0;
        lastPos = p.getPos();
    }

    /**
     * Dojscie na pierwsza klatke. Zadanie: stanac DOKLADNIE na starcie i z zerowa predkoscia,
     * bo kazdy blad startowy (pozycja/pedzie) przenosi sie na cala trase.
     */
    private void approach(MinecraftClient mc, ClientPlayerEntity p) {
        if (abortIfScreen(mc)) return;
        GameOptions o = mc.options;
        double[] f0 = frames.get(0);
        Vec3d pos = p.getPos();
        approachTicks++;

        double dx = f0[0] - pos.x;
        double dz = f0[2] - pos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        Vec3d vel = p.getVelocity();
        double speed = Math.hypot(vel.x, vel.z);
        boolean yOk = Math.abs(f0[1] - pos.y) < 1.0;

        boolean strict = dist < START_REACH && speed < START_SPEED;
        boolean relaxed = approachTicks > APPROACH_RELAX_TICKS && dist < RELAXED_REACH && speed < 0.03;
        if (yOk && p.isOnGround() && (strict || relaxed)) {
            releaseKeys(mc);
            p.setYaw((float) f0[3]);
            p.setPitch((float) f0[4]);
            idx = 0;
            state = State.REPLAY;
            return;
        }

        // patrz w strone celu (przy samym celu zostaw yaw, zeby nie drzec)
        if (dist > 0.05) {
            p.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f);
        }
        p.setPitch(0.0f);

        // predkosc skladowa w strone celu i droga wybiegu (tarcie ~0.546/tick => ~1.2*v)
        double radial = dist > 1e-6 ? (vel.x * dx + vel.z * dz) / dist : 0.0;
        double coast = Math.max(0.0, radial) * 1.2;
        double remaining = dist - coast;

        boolean forward = remaining > 0.04;
        boolean sneak = dist < SNEAK_RANGE && p.isOnGround();
        boolean sprint = forward && dist > 4.0;

        o.forwardKey.setPressed(forward);
        o.sneakKey.setPressed(sneak);
        o.sprintKey.setPressed(sprint);

        boolean jump = (p.isOnGround() && (f0[1] > pos.y + 0.5 || p.horizontalCollision)) || p.isTouchingWater();
        o.jumpKey.setPressed(jump);

        // wykrywanie utkniecia
        if (pos.squaredDistanceTo(lastPos) < 0.0001 && forward) stuck++; else stuck = 0;
        lastPos = pos;
        if (stuck > STUCK_LIMIT) {
            stopBot(mc);
            msg(p, "Bot utknal w drodze na start - zatrzymano.");
        }
    }

    /** Odtwarzanie ze sprzezeniem zwrotnym. */
    private void replay(MinecraftClient mc, ClientPlayerEntity p) {
        if (abortIfScreen(mc)) return;

        if (idx > 0) {
            Vec3d pos = p.getPos();
            double[] prev = frames.get(idx - 1);
            double err = pos.distanceTo(new Vec3d(prev[0], prev[1], prev[2]));

            if (err > RESYNC_DIST) {
                // 1) probuj lokalnie, 2) dopiero potem globalnie
                int lo = Math.max(0, idx - 40);
                int hi = Math.min(frames.size() - 1, idx + 40);
                int n = nearestFrame(pos, lo, hi);
                double d = distTo(pos, frames.get(n));
                if (d > RESYNC_DIST) {
                    n = nearestFrame(pos, 0, frames.size() - 1);
                    d = distTo(pos, frames.get(n));
                }
                if (d > LOST_DIST) {
                    stopBot(mc);
                    msg(p, "Bot zgubil trase - zatrzymano.");
                    return;
                }
                idx = n + 1;
            } else {
                // lokalna synchronizacja fazy: lag / kolizja potrafia przesunac nas wzgledem nagrania
                int lo = Math.max(0, idx - 1 - SYNC_BACK);
                int hi = Math.min(frames.size() - 1, idx - 1 + SYNC_FWD);
                int best = nearestFrame(pos, lo, hi);
                if (best != idx - 1 && distTo(pos, frames.get(best)) < err - SYNC_MARGIN) {
                    idx = best + 1;
                }
            }
        }

        if (idx >= frames.size()) {
            loops++;
            releaseKeys(mc);
            beginApproach(p);
            return;
        }

        double[] f = frames.get(idx);
        int flags = (int) f[5];
        double corr = steerCorrection(p, idx, flags);
        idx++;

        GameOptions o = mc.options;
        p.setYaw((float) (f[3] + corr));
        p.setPitch((float) f[4]);

        o.forwardKey.setPressed((flags & FWD) != 0);
        o.backKey.setPressed((flags & BACK) != 0);
        o.leftKey.setPressed((flags & LEFT) != 0);
        o.rightKey.setPressed((flags & RIGHT) != 0);
        o.jumpKey.setPressed((flags & JUMP) != 0);
        o.sprintKey.setPressed((flags & SPRINT) != 0);
        o.sneakKey.setPressed((flags & SNEAK) != 0);
        applyClick(o.attackKey, (flags & ATTACK) != 0, (lastFlags & ATTACK) != 0);
        applyClick(o.useKey, (flags & USE) != 0, (lastFlags & USE) != 0);
        lastFlags = flags;

        int slot = (int) f[6];
        if (slot >= 0 && slot <= 8) p.getInventory().selectedSlot = slot;
    }

    /**
     * Korekcja yaw (stopnie), ktora skreca ruch tak, aby po nastepnym ticku wyladowac
     * w nagranej pozycji. Ruch wzgledem yaw jest staly, wiec obrot yaw obraca caly wektor ruchu
     * (dziala tez dla strafe / back).
     */
    private double steerCorrection(ClientPlayerEntity p, int i, int flags) {
        if (i <= 0) return 0.0;
        if ((flags & (ATTACK | USE)) != 0) return 0.0; // nie psuj celowania w trakcie klikniec

        double[] cur = frames.get(i);
        double[] prev = frames.get(i - 1);

        // nagrane przemieszczenie w tym ticku
        double rx = cur[0] - prev[0];
        double rz = cur[2] - prev[2];
        if (rx * rx + rz * rz < 0.03 * 0.03) return 0.0; // stoimy - brak kierunku do skorygowania

        Vec3d pos = p.getPos();
        double ex = prev[0] - pos.x;
        double ez = prev[2] - pos.z;
        if (ex * ex + ez * ez < STEER_MIN_ERR * STEER_MIN_ERR) return 0.0;

        // gdzie chcemy byc po ticku, wzgledem aktualnej pozycji
        double wx = cur[0] - pos.x;
        double wz = cur[2] - pos.z;
        if (wx * wx + wz * wz < 1e-6) return 0.0;

        double delta = Math.atan2(wz, wx) - Math.atan2(rz, rx);
        while (delta > Math.PI) delta -= 2 * Math.PI;
        while (delta < -Math.PI) delta += 2 * Math.PI;

        double deg = Math.toDegrees(delta) * STEER_GAIN;
        return Math.max(-STEER_MAX_DEG, Math.min(STEER_MAX_DEG, deg));
    }

    private void applyClick(KeyBinding key, boolean now, boolean before) {
        key.setPressed(now);
        if (now && !before) {
            KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(key.getBoundKeyTranslationKey()));
        }
    }

    private static double distTo(Vec3d pos, double[] f) {
        return pos.distanceTo(new Vec3d(f[0], f[1], f[2]));
    }

    private int nearestFrame(Vec3d pos, int from, int to) {
        int best = from;
        double bestD = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            double[] f = frames.get(i);
            double d = pos.squaredDistanceTo(f[0], f[1], f[2]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // ----------------------------------------------------------------- utils

    private boolean abortIfScreen(MinecraftClient mc) {
        if (mc.currentScreen != null) {
            stopBot(mc);
            return true;
        }
        return false;
    }

    private void stopBot(MinecraftClient mc) {
        if (state == State.APPROACH || state == State.REPLAY) releaseKeys(mc);
        if (state != State.RECORDING) state = State.IDLE;
    }

    private void releaseKeys(MinecraftClient mc) {
        GameOptions o = mc.options;
        o.forwardKey.setPressed(false);
        o.backKey.setPressed(false);
        o.leftKey.setPressed(false);
        o.rightKey.setPressed(false);
        o.jumpKey.setPressed(false);
        o.sprintKey.setPressed(false);
        o.sneakKey.setPressed(false);
        o.attackKey.setPressed(false);
        o.useKey.setPressed(false);
        lastFlags = 0;
    }

    private void msg(ClientPlayerEntity p, String s) {
        p.sendMessage(Text.literal("[PathBot] ").formatted(Formatting.GOLD)
                .append(Text.literal(s).formatted(Formatting.WHITE)), false);
    }

    private void save() {
        try {
            Files.writeString(file, gson.toJson(frames));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                double[][] arr = gson.fromJson(Files.readString(file), double[][].class);
                frames = new ArrayList<>();
                if (arr != null) {
                    for (double[] a : arr) {
                        if (a.length >= 7) {
                            frames.add(a);
                        } else if (a.length == 6) {
                            frames.add(new double[]{a[0], a[1], a[2], a[3], a[4], a[5], -1});
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ------------------------------------------------------------------ info

    private void sendInfo(FabricClientCommandSource src) {
        src.sendFeedback(Text.literal("=== Ucieczka Bot ===").formatted(Formatting.GOLD, Formatting.BOLD));
        src.sendFeedback(Text.literal("Nagrywa Twoja trase (ruch, skoki, sprint, klikniecia, slot paska) i odtwarza ja w petli."));
        src.sendFeedback(infoLine(Text.literal("R"), "start/stop nagrywania. Trasa zapisuje sie do config/pathbot.json."));
        src.sendFeedback(infoLine(Text.literal("P"), "start/stop bota. Bot podchodzi na start, odtwarza trase i wraca na poczatek - w kolko."));
        src.sendFeedback(infoLine(Text.literal("V"), "pokaz/ukryj particlesy trasy."));
        src.sendFeedback(Text.literal("Particlesy: zielony slup = start, ogien = koniec, biale = trasa, iskry = atak, niebieski ogien = uzycie (prawy przycisk)."));
        src.sendFeedback(Text.literal("Wskazowki: zakoncz nagrywanie blisko startu, zeby petla byla plynna. Otwarcie czatu lub menu zatrzymuje bota. Jesli bot utknie albo zgubi trase, sam sie zatrzyma."));
        src.sendFeedback(Text.literal("Klawisze zmienisz w Ustawienia -> Sterowanie -> PathBot."));
    }

    private Text infoLine(Text key, String desc) {
        return Text.literal("[").formatted(Formatting.GRAY)
                .append(key.copy().formatted(Formatting.YELLOW))
                .append(Text.literal("] ").formatted(Formatting.GRAY))
                .append(Text.literal(desc).formatted(Formatting.WHITE));
    }

    // ------------------------------------------------------------- particles

    private void drawPath(MinecraftClient mc, ClientPlayerEntity p) {
        if (!showPath || frames.size() < 2) return;
        if (++particleTick % 4 != 0) return;

        Vec3d me = p.getPos();
        int n = frames.size();
        for (int i = 0; i < n; i++) {
            double[] f = frames.get(i);
            if (me.squaredDistanceTo(f[0], f[1], f[2]) > 1600.0) continue;
            int flags = (int) f[5];

            ParticleEffect type = null;
            if ((flags & ATTACK) != 0) type = ParticleTypes.ELECTRIC_SPARK;
            else if ((flags & USE) != 0) type = ParticleTypes.SOUL_FIRE_FLAME;
            else if (i % 5 == 0) type = ParticleTypes.END_ROD;
            if (type != null) {
                mc.particleManager.addParticle(type, f[0], f[1] + 0.1, f[2], 0, 0, 0);
            }
        }
        double[] s = frames.get(0);
        double[] e = frames.get(n - 1);
        for (int k = 0; k < 6; k++) {
            mc.particleManager.addParticle(ParticleTypes.HAPPY_VILLAGER, s[0], s[1] + 0.3 + k * 0.5, s[2], 0, 0, 0);
        }
        mc.particleManager.addParticle(ParticleTypes.FLAME, e[0], e[1] + 0.3, e[2], 0, 0.02, 0);
    }
}
