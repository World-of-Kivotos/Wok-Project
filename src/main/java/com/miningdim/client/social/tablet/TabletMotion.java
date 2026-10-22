package com.miningdim.client.social.tablet;

import com.miningdim.client.social.tablet.TabletAnimationModel.PhaseSeg;
import com.miningdim.client.social.tablet.TabletAnimationModel.Phases;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The state machine and progress axis of the animation (DESIGN 2.2–2.5, 3.2–3.4, 4), a port of the
 * preview's {@code page/motion.js} {@code create()}. One instance for the whole client (screens
 * are reused, so nothing may live on a screen instance). Pure logic: the caller passes the time
 * ({@code System.nanoTime() / 1e6}, milliseconds) into every call.
 *
 * <p>States IDLE → OPENING → SHOWN → CLOSING → IDLE. Opening and closing run the same p → pose
 * function with different time → p maps, so a reversal continues from the current p without a jump.
 * {@link #curve()} remembers which pose curve this round trip uses (opened from IDLE = open curve,
 * closed from SHOWN = close curve, a reversal keeps the current one). Sounds, cuts and state
 * changes are queued as {@link Event}s for {@link #drainEvents()}.
 *
 * <p>Java additions (not in the preview): {@link #switchPath} (IMPL_PLAN D17: fall back to B on the
 * first frame), {@link #carry} (D4: the sink of a wake-band close fades over 100 ms) and
 * {@link #freeze} / {@link #unfreeze} (uiTest freeze frames).
 */
public final class TabletMotion {
    public enum State { IDLE, OPENING, SHOWN, CLOSING }

    /** Which p → pose curve a round trip uses (DESIGN 3.4, review fix: two curves). */
    public enum Curve { OPEN, CLOSE }

    /** Interrupts of §3.4. */
    public enum Interrupt {
        FIRE, AIM, HOTBAR, DEATH, DIMENSION, VEHICLE, CAMERA;

        /** Whether it also ends an animation that is still opening. */
        public boolean whileOpening() {
            return this == DEATH || this == DIMENSION || this == VEHICLE || this == CAMERA;
        }
    }

    /** Inputs of §2.5. */
    public enum Input { ESC, TERMINAL, MAP_KEY, KEY, HOTBAR, MOUSE_DOWN, SCROLL, DRAG, MOUSE_UP, MOUSE_MOVE }

    /** Result of the screen-opening rule (§2.4). */
    public enum Enter { OPEN, SHOW, RESET, NONE }

    /** What a screen is for the enter / leave rules; {@code null} means no screen (the world). */
    public enum Kind { TABLET, OTHER }

    /** What an input does to the animation. */
    public enum Effect { NONE, CLOSE, FINISH, JUMP_SHOWN, INTERRUPT, REOPEN, PAUSE, OPEN }

    /** How an input reaches the screen. */
    public enum Deliver { RAW, REMAPPED, NONE }

    /** Which sounds a cut fades out. */
    public enum Cut {
        OPEN, CLOSE, ALL;

        public String previewName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * The settings an animation is started with.
     *
     * @param mode    {@code ui.tabletAnimation}
     * @param degrade play B instead of A (no first-person hand pass)
     * @param hand    main hand when the tablet came out
     * @param capture C1 close (replays the last screen frame); on by default
     * @param screen  the kind of screen being animated
     */
    public record Config(TabletMode mode, boolean degrade, TabletHand hand, boolean capture,
                         TabletScreenKind screen) {
        public static final Config DEFAULT = new Config(TabletMode.FULL, false, TabletHand.GUN,
                TabletAnimationModel.CAPTURE_DEFAULT, TabletScreenKind.SQUAD);

        public Config {
            mode = mode == null ? TabletMode.FULL : mode;
            hand = hand == null ? TabletHand.GUN : hand;
            screen = screen == null ? TabletScreenKind.SQUAD : screen;
        }

        public Config withMode(TabletMode value) {
            return new Config(value, degrade, hand, capture, screen);
        }

        public Config withDegrade(boolean value) {
            return new Config(mode, value, hand, capture, screen);
        }

        public Config withHand(TabletHand value) {
            return new Config(mode, degrade, value, capture, screen);
        }

        public Config withCapture(boolean value) {
            return new Config(mode, degrade, hand, value, screen);
        }

        public Config withScreen(TabletScreenKind value) {
            return new Config(mode, degrade, hand, capture, value);
        }
    }

    /** Where this close started: p, the curve, and whether it replays a captured frame (C1). */
    public record CloseFrom(double p, Curve curve, boolean capture) {
    }

    /**
     * One queued event.
     *
     * @param type  state change, sound or cut
     * @param name  STATE: the {@link State} name; SOUND: the sound name; CUT: open / close / all
     * @param atMs  when it happened
     * @param dir   SOUND only: the cue's direction
     * @param link  SOUND only: "ready", which the controller plays only with a battle link
     */
    public record Event(Type type, String name, double atMs, TabletCue.Dir dir, boolean link) {
        public enum Type { STATE, SOUND, CUT }
    }

    /** Result of {@link #inputAction}. */
    public record InputDecision(boolean intercept, Deliver deliver, Effect effect) {
    }

    /**
     * What {@link TabletPose3D#frame} needs from the motion.
     *
     * @param captureFromP the p this C1 close started from; NaN when not closing
     * @param capture      whether this close replays a captured frame
     */
    public record PoseContext(Curve curve, double captureFromP, boolean capture) {
    }

    /** A remapped mouse point (preview {@code remapPoint}). */
    public record Remap(double x, double y, boolean inside) {
    }

    private static final double EPS = 1.0E-9D;

    private Config cfg;
    private State state = State.IDLE;
    private double p;
    private int dir;
    private TabletPath path;
    private TabletTimeMap map;
    private double t0;
    private boolean fast;
    private Curve curve = Curve.OPEN;
    private CloseFrom closeFrom;
    private double lastIdleAt = Double.NEGATIVE_INFINITY;
    private final List<Event> events = new ArrayList<>();
    private final Map<String, Double> lastSound = new HashMap<>();
    private boolean quiet;
    private boolean frozen;
    private double frozenP;
    private double carryStart;
    private double carryAt = Double.NaN;

    public TabletMotion(Config config) {
        this.cfg = config == null ? Config.DEFAULT : config;
    }

    public TabletMotion() {
        this(Config.DEFAULT);
    }

    /** Replaces the settings (the next open picks the path from them). */
    public void configure(Config config) {
        this.cfg = Objects.requireNonNull(config, "config");
    }

    public Config config() {
        return cfg;
    }

    // ---- events -------------------------------------------------------------------------------

    /** The events queued since the last call, oldest first. */
    public List<Event> drainEvents() {
        List<Event> out = List.copyOf(events);
        events.clear();
        return out;
    }

    private void emit(Event event) {
        events.add(event);
    }

    private List<TabletCue> cueList() {
        return path == null ? List.of() : TabletCues.forPath(path, cfg.screen());
    }

    private void fire(TabletCue cue, double now) {
        if (quiet && !TabletCues.FINISH_KEEP.contains(cue.name())) {
            return;
        }
        Double last = lastSound.get(cue.name());
        if (last != null && now - last < TabletAnimationModel.SFX_NO_REPEAT_MS) {
            return;
        }
        lastSound.put(cue.name(), now);
        emit(new Event(Event.Type.SOUND, cue.name(), now, cue.dir(), cue.link()));
    }

    private void cut(Cut which, double now) {
        emit(new Event(Event.Type.CUT, which.previewName(), now, null, false));
    }

    /** Fires the cues whose atP the progress crossed from {@code fromP} to {@code toP}. */
    private void cues(double fromP, double toP, double now) {
        List<TabletCue> list = cueList();
        if (list.isEmpty() || fromP == toP) {
            return;
        }
        TabletCue.Dir direction = toP > fromP ? TabletCue.Dir.OPEN : TabletCue.Dir.CLOSE;
        for (TabletCue cue : list) {
            if (cue.dir() != direction || !cue.hasAtP() || !cue.playsFor(cfg.hand())) {
                continue;
            }
            double at = cue.atP();
            boolean crossed = direction == TabletCue.Dir.OPEN ? fromP < at && toP >= at
                    : fromP > at && toP <= at;
            if (crossed) {
                fire(cue, now);
            }
        }
    }

    /** The start frame: cues whose startRange holds {@code atP} play as well. */
    private void startCues(double atP, int direction, double now) {
        TabletCue.Dir dirName = direction > 0 ? TabletCue.Dir.OPEN : TabletCue.Dir.CLOSE;
        for (TabletCue cue : cueList()) {
            if (cue.dir() == dirName && cue.playsFor(cfg.hand()) && cue.startsAt(atP)) {
                fire(cue, now);
            }
        }
    }

    private void setState(State next, double now) {
        if (next != State.OPENING) {
            quiet = false;
        }
        if (state != next) {
            state = next;
            emit(new Event(Event.Type.STATE, next.name(), now, null, false));
        }
    }

    private void startMap(TabletTimeMap next, double now, double startP, int direction) {
        map = next;
        dir = direction;
        double t = next.invert(startP);
        t0 = now - t;
        double prev = p;
        p = next.eval(t);
        cues(direction > 0 ? Math.min(prev, p) - 1.0E-6D : Math.max(prev, p) + 1.0E-6D, p, now);
        startCues(p, direction, now);
    }

    // ---- transitions --------------------------------------------------------------------------

    /**
     * Opens (the key opened a tablet screen from the world, or the enter rule said OPEN). While
     * closing it plays forward from the current p (×0.6). Returns {@code false} when it is already
     * opening or shown.
     */
    public boolean open(double now) {
        if (state == State.OPENING || state == State.SHOWN) {
            return false;
        }
        boolean reversing = state == State.CLOSING;
        if (!reversing) {
            path = pathFor(cfg);
            curve = Curve.OPEN;
        }
        closeFrom = null;
        boolean full = cfg.mode() == TabletMode.FULL
                && (path == TabletPath.A3D || path == TabletPath.B2D);
        fast = full && (reversing || now - lastIdleAt < TabletAnimationModel.REOPEN_WINDOW_MS);
        if (reversing) {
            cut(Cut.CLOSE, now);
        }
        if (path == TabletPath.OFF) {
            p = 1.0D;
            dir = 0;
            map = null;
            setState(State.SHOWN, now);
            return true;
        }
        TabletTimeMap next = openMap(path, cfg.hand(), fast);
        double startP = reversing ? p : next.startP();
        quiet = false;
        setState(State.OPENING, now);
        startMap(next, now, Math.max(startP, next.startP()), 1);
        return true;
    }

    /** Straight to SHOWN (entered from another screen, OFF, jump). A later close takes the close curve. */
    public void show(double now) {
        if (path == null) {
            path = pathFor(cfg);
        }
        p = 1.0D;
        dir = 0;
        map = null;
        closeFrom = null;
        clearCarry();
        setState(State.SHOWN, now);
    }

    /** A click while opening: SHOWN within one frame; of the skipped sounds only "ready" plays. */
    public void jumpShown(double now) {
        if (state != State.OPENING) {
            return;
        }
        double prev = p;
        cut(Cut.OPEN, now);
        show(now);
        for (TabletCue cue : cueList()) {
            if (cue.dir() == TabletCue.Dir.OPEN && cue.hasAtP()
                    && TabletCues.FINISH_KEEP.contains(cue.name()) && prev < cue.atP()) {
                fire(cue, now);
            }
        }
    }

    /** Another key while opening: finish within 100 ms (easeOutQuad); only "ready" may play. */
    public void finish(double now) {
        if (state != State.OPENING || map == null) {
            return;
        }
        double remain = map.durMs() - (now - t0);
        if (remain <= TabletAnimationModel.KEY_FINISH_MS) {
            return;
        }
        map = new TabletTimeMap(new double[][]{{0.0D, p}, {TabletAnimationModel.KEY_FINISH_MS, 1.0D}},
                TabletAnimationModel.KEY_FINISH_EASE, TabletAnimationModel.KEY_FINISH_MS);
        t0 = now;
        quiet = true;
        cut(Cut.OPEN, now);
    }

    /**
     * Closes (the leave rule saw the tablet screen go). From SHOWN it takes the close curve; while
     * opening it plays the open curve back. Without C1 A starts at READ (0.61); with C1 a close in
     * the wake band (0.61 &lt; p &lt; 0.74) does not capture and starts at 0.61, carrying the sink
     * along (D4). Returns {@code false} when idle or already closing.
     */
    public boolean close(double now) {
        if (state == State.IDLE || state == State.CLOSING) {
            return false;
        }
        if (path == null) {
            path = pathFor(cfg);
        }
        boolean fromOpening = state == State.OPENING;
        if (fromOpening) {
            cut(Cut.OPEN, now);
        }
        if (path == TabletPath.OFF) {
            p = 0.0D;
            dir = 0;
            map = null;
            lastIdleAt = now;
            clearCarry();
            setState(State.IDLE, now);
            return true;
        }
        if (!fromOpening) {
            curve = Curve.CLOSE;
        }
        TabletTimeMap next = closeMap(path, cfg.hand(), cfg.capture(), cfg.screen());
        double cap = closeStartCap(path, cfg.capture());
        boolean skipWake = path == TabletPath.A3D && cfg.capture()
                && p > TabletAnimationModel.CAPTURE_SKIP_WAKE_FROM + EPS
                && p < TabletAnimationModel.CAPTURE_SKIP_WAKE_TO - EPS;
        if (skipWake) {
            cap = TabletAnimationModel.CAPTURE_SKIP_WAKE_FROM;
            if (curve == Curve.OPEN) {
                carryStart = TabletAnimationModel.breathAt(p);
                carryAt = now;
            }
        }
        double startP = Math.min(Math.min(p, cap), next.startP());
        closeFrom = new CloseFrom(startP, curve, path == TabletPath.A3D && cfg.capture() && !skipWake);
        setState(State.CLOSING, now);
        startMap(next, now, startP, -1);
        return true;
    }

    /** Straight to IDLE; {@code completed} counts as a finished close for the 2-second reopen. */
    public State reset(double now, boolean completed) {
        State was = state;
        if (was == State.OPENING || was == State.CLOSING) {
            cut(Cut.ALL, now);
        }
        p = 0.0D;
        dir = 0;
        map = null;
        closeFrom = null;
        curve = Curve.OPEN;
        clearCarry();
        if (completed) {
            lastIdleAt = now;
        }
        setState(State.IDLE, now);
        return was;
    }

    /** §3.4: any interrupt ends a close; only death, dimension, vehicle and camera end an opening. */
    public boolean interrupt(double now, Interrupt cause) {
        if (state == State.CLOSING) {
            reset(now, true);
            return true;
        }
        if (state == State.OPENING && cause != null && cause.whileOpening()) {
            reset(now, false);
            return true;
        }
        return false;
    }

    /** §3.7: A lost the first-person hand pass mid-way: an opening is shown, a close is done. */
    public boolean loseHandRender(double now) {
        if (path != TabletPath.A3D) {
            return false;
        }
        if (state == State.OPENING) {
            show(now);
            return true;
        }
        if (state == State.CLOSING) {
            reset(now, true);
            return true;
        }
        return false;
    }

    /**
     * Java addition (IMPL_PLAN D17): on the first frame of an opening the hand pass did not run, so
     * the opening restarts from p 0 on {@code next} (normally B). Only while opening and at most one
     * frame after it started; returns whether it switched.
     */
    public boolean switchPath(TabletPath next, double now) {
        if (state != State.OPENING || map == null || next == null || next == path
                || next == TabletPath.OFF
                || now - t0 > TabletAnimationModel.FRAME_MS + 1.0E-6D) {
            return false;
        }
        cut(Cut.OPEN, now);
        path = next;
        cfg = cfg.withDegrade(next == TabletPath.B2D);
        curve = Curve.OPEN;
        TabletTimeMap restart = openMap(next, cfg.hand(), fast);
        startMap(restart, now, restart.startP(), 1);
        return true;
    }

    /** Advances to {@code now}. */
    public TabletMotion update(double now) {
        if (state != State.OPENING && state != State.CLOSING) {
            return this;
        }
        if (frozen) {
            p = frozenP;
            return this;
        }
        double t = now - t0;
        double prev = p;
        p = TabletEasing.clamp01(map.eval(t));
        cues(prev, p, now);
        if (t >= map.durMs()) {
            if (state == State.OPENING) {
                p = 1.0D;
                dir = 0;
                map = null;
                setState(State.SHOWN, now);
            } else {
                double end = t0 + map.durMs();
                p = 0.0D;
                dir = 0;
                map = null;
                lastIdleAt = end;
                closeFrom = null;
                curve = Curve.OPEN;
                clearCarry();
                setState(State.IDLE, now);
            }
        }
        return this;
    }

    // ---- enter / leave (§2.4) ----------------------------------------------------------------

    /** {@code ScreenEvent.Opening} (LOWEST): applies {@link #enterAction} and returns it. */
    public Enter onScreenOpening(Kind from, Kind to, boolean inWorld, double now) {
        Enter act = enterAction(from, to, inWorld);
        switch (act) {
            case OPEN -> open(now);
            case SHOW -> show(now);
            case RESET -> {
                if (state != State.IDLE) {
                    reset(now, false);
                }
            }
            case NONE -> {
            }
        }
        return act;
    }

    /** {@code RenderTickEvent} START: closes when the tablet screen of the last frame is gone. */
    public boolean onRenderTick(Kind previous, Kind current, double now) {
        if (leaveAction(previous, current)) {
            close(now);
            return true;
        }
        return false;
    }

    // ---- Java additions -----------------------------------------------------------------------

    /**
     * D4: the sink carried into a wake-band close (screen fraction, positive = down), fading to 0
     * over {@link TabletAnimationModel#SKIP_WAKE_CARRY_MS}; 0 when nothing is carried.
     */
    public double carry(double now) {
        if (Double.isNaN(carryAt) || carryStart == 0.0D
                || state != State.CLOSING && state != State.OPENING) {
            return 0.0D;
        }
        double t = now - carryAt;
        if (t >= TabletAnimationModel.SKIP_WAKE_CARRY_MS) {
            return 0.0D;
        }
        double left = 1.0D - TabletAnimationModel.SKIP_WAKE_CARRY_EASE.apply(
                Math.min(1.0D, t / TabletAnimationModel.SKIP_WAKE_CARRY_MS));
        return carryStart * left;
    }

    private void clearCarry() {
        carryStart = 0.0D;
        carryAt = Double.NaN;
    }

    /** uiTest: hold the progress at {@code atP}; {@link #update} no longer advances or emits. */
    public void freeze(double atP) {
        frozen = true;
        frozenP = TabletEasing.clamp01(atP);
        if (state == State.OPENING || state == State.CLOSING) {
            p = frozenP;
        }
    }

    /** Continues from the frozen progress with the time that is left. */
    public void unfreeze(double now) {
        if (!frozen) {
            return;
        }
        frozen = false;
        if ((state == State.OPENING || state == State.CLOSING) && map != null) {
            t0 = now - map.invert(p);
        }
    }

    public boolean frozen() {
        return frozen;
    }

    // ---- queries ------------------------------------------------------------------------------

    public State state() {
        return state;
    }

    public double p() {
        return p;
    }

    /** 1 opening, −1 closing, 0 otherwise. */
    public int dir() {
        return dir;
    }

    /** The path of the current (or last) animation; {@code null} before the first one. */
    public TabletPath path() {
        return path;
    }

    public Curve curve() {
        return curve;
    }

    /** Whether this opening runs ×0.6 (reopened within 2 s, or reversed). */
    public boolean fast() {
        return fast;
    }

    public CloseFrom closeFrom() {
        return closeFrom;
    }

    /** Time since the current map started (0 when no map runs). */
    public double elapsed(double now) {
        return map == null ? 0.0D : Math.max(0.0D, now - t0);
    }

    /** Length of the current map (0 when none runs). */
    public double durMs() {
        return map == null ? 0.0D : map.durMs();
    }

    /** Whether an animation is running (opening or closing). */
    public boolean animating() {
        return state == State.OPENING || state == State.CLOSING;
    }

    /** What {@link TabletPose3D#frame} needs: curve, and for a C1 close its start p. */
    public PoseContext poseContext() {
        if (state == State.CLOSING && closeFrom != null) {
            return new PoseContext(curve, closeFrom.p(), closeFrom.capture() && cfg.capture());
        }
        return new PoseContext(curve, Double.NaN, false);
    }

    /** The status-bar phase name of the current frame. */
    public String phaseName() {
        TabletPath current = path != null ? path : pathFor(cfg);
        Phases phases = TabletAnimationModel.PHASES.get(current.name());
        if (state == State.IDLE) {
            return phases.closeEnd();
        }
        if (state == State.SHOWN) {
            return phases.openEnd();
        }
        return phaseName(current, dir, p, cfg.hand(), cfg.screen());
    }

    // ---- pure rules ---------------------------------------------------------------------------

    /** Which path the settings play. */
    public static TabletPath pathFor(Config config) {
        if (config.mode() == TabletMode.OFF) {
            return TabletPath.OFF;
        }
        if (config.mode() == TabletMode.QUICK) {
            return TabletPath.QUICK;
        }
        return config.degrade() ? TabletPath.B2D : TabletPath.A3D;
    }

    /** The open map; {@code fast} (×0.6) applies to the full setting's A and B only. */
    public static TabletTimeMap openMap(TabletPath path, TabletHand hand, boolean fast) {
        double k = fast && (path == TabletPath.A3D || path == TabletPath.B2D)
                ? TabletAnimationModel.REOPEN_SCALE : 1.0D;
        return switch (path) {
            case A3D -> TabletTimeMap.of(TabletAnimationModel.openPts(hand == TabletHand.EMPTY), k);
            case B2D -> TabletTimeMap.of(TabletAnimationModel.bOpenPts(), k);
            case QUICK -> TabletTimeMap.of(TabletAnimationModel.quickOpenPts(), 1.0D);
            case OFF -> TabletTimeMap.of(new double[][]{{0, 1}}, 1.0D);
        };
    }

    /** The close map. */
    public static TabletTimeMap closeMap(TabletPath path, TabletHand hand, boolean capture,
                                         TabletScreenKind screen) {
        boolean empty = hand == TabletHand.EMPTY;
        return switch (path) {
            case A3D -> TabletTimeMap.of(capture ? TabletAnimationModel.closeCapturePts(empty)
                    : TabletAnimationModel.closePts(empty), 1.0D);
            case B2D -> TabletTimeMap.of(TabletAnimationModel.bClosePts(
                    screen == null || screen.isTerminal()), 1.0D);
            case QUICK -> TabletTimeMap.of(TabletAnimationModel.quickClosePts(), 1.0D);
            case OFF -> TabletTimeMap.of(new double[][]{{0, 0}}, 1.0D);
        };
    }

    /** How high a close may start: A without C1 starts at READ (0.61), everything else at 1. */
    public static double closeStartCap(TabletPath path, boolean capture) {
        if (path == TabletPath.A3D && !capture) {
            return TabletAnimationModel.closePts(false)[0][1];
        }
        return 1.0D;
    }

    private static String phaseKey(TabletPath path, TabletScreenKind screen) {
        boolean map = screen != null && !screen.isTerminal();
        return (path == TabletPath.B2D || path == TabletPath.QUICK) && map
                ? path.name() + "_MAP" : path.name();
    }

    private static List<PhaseSeg> phaseList(Phases phases, int direction, TabletHand hand) {
        if (hand == TabletHand.EMPTY) {
            List<PhaseSeg> alt = direction >= 0 ? phases.openEmpty() : phases.closeEmpty();
            if (alt != null) {
                return alt;
            }
        }
        return direction >= 0 ? phases.open() : phases.close();
    }

    /**
     * Phase name at {@code p}: opening by [from, to) (p ≥ 1 is "就绪"), closing by (from, to]
     * (p ≤ 0 is "空闲"). {@code p} is rounded to 1e-9 first.
     */
    public static String phaseName(TabletPath path, int direction, double atP, TabletHand hand,
                                   TabletScreenKind screen) {
        Phases phases = TabletAnimationModel.PHASES.get(phaseKey(path, screen));
        if (phases == null) {
            return "";
        }
        double q = Math.round(atP * 1e9) / 1e9;
        List<PhaseSeg> list = phaseList(phases, direction, hand);
        if (direction >= 0) {
            if (q >= 1.0D) {
                return phases.openEnd();
            }
            for (PhaseSeg seg : list) {
                if (q >= seg.from() && q < seg.to()) {
                    return seg.name();
                }
            }
            return list.isEmpty() ? phases.openEnd() : list.get(0).name();
        }
        if (q <= 0.0D) {
            return phases.closeEnd();
        }
        for (PhaseSeg seg : list) {
            if (q > seg.from() && q <= seg.to()) {
                return seg.name();
            }
        }
        return phases.closeEnd();
    }

    /**
     * §2.4, {@code ScreenEvent.Opening} (LOWEST): {@code null} = no screen (in the world).
     * world → tablet opens (shows when not in a world yet); tablet <-> tablet does nothing; another
     * screen → tablet shows; tablet → another screen, or anything opening during a close, resets.
     */
    public static Enter enterAction(Kind from, Kind to, boolean inWorld) {
        if (to == Kind.TABLET) {
            if (from == Kind.TABLET) {
                return Enter.NONE;
            }
            if (from == null) {
                return inWorld ? Enter.OPEN : Enter.SHOW;
            }
            return Enter.SHOW;
        }
        if (from == Kind.TABLET) {
            return Enter.RESET;
        }
        if (from == null) {
            return Enter.RESET;
        }
        return Enter.NONE;
    }

    /** §2.4: the tablet screen of the last frame is gone and nothing replaced it. */
    public static boolean leaveAction(Kind previous, Kind current) {
        return previous == Kind.TABLET && current == null;
    }

    /**
     * §2.5 input rules. {@code visible}: whether the pointer lands on a part of the screen that is
     * already visible ({@link TabletPath2D#termVisible} / {@link TabletPose3D#contentVisible});
     * a click on hidden glass is swallowed and only jumps to the end even past p★.
     */
    public static InputDecision inputAction(TabletPath path, State state, double atP, Input input,
                                            TabletScreenKind screen, boolean visible) {
        boolean ignoresTerminalKey = screen == null || !screen.closesOnTerminalKey();
        double pStar = TabletAnimationModel.pStar(path);
        if (input == Input.MOUSE_MOVE) {
            return pass(Effect.NONE);
        }
        if (state == State.IDLE) {
            return input == Input.TERMINAL || input == Input.MAP_KEY ? pass(Effect.OPEN)
                    : pass(Effect.NONE);
        }
        if (state == State.SHOWN) {
            if (input == Input.TERMINAL) {
                return pass(ignoresTerminalKey ? Effect.NONE : Effect.CLOSE);
            }
            return input == Input.ESC ? pass(Effect.CLOSE) : pass(Effect.NONE);
        }
        if (state == State.OPENING) {
            if (input == Input.ESC) {
                return pass(Effect.CLOSE);
            }
            if (input == Input.TERMINAL) {
                return pass(ignoresTerminalKey ? Effect.NONE : Effect.CLOSE);
            }
            if (input == Input.KEY || input == Input.HOTBAR || input == Input.MAP_KEY) {
                return pass(Effect.FINISH);
            }
            if (input == Input.MOUSE_UP) {
                return pass(Effect.NONE);
            }
            if (input == Input.MOUSE_DOWN || input == Input.SCROLL || input == Input.DRAG) {
                if (Double.isNaN(pStar) || atP < pStar || !visible) {
                    return new InputDecision(true, Deliver.NONE, Effect.JUMP_SHOWN);
                }
                return new InputDecision(true, Deliver.REMAPPED, Effect.JUMP_SHOWN);
            }
            return pass(Effect.NONE);
        }
        // CLOSING: never intercepted
        return switch (input) {
            case ESC -> pass(Effect.PAUSE);
            case TERMINAL, MAP_KEY -> pass(Effect.REOPEN);
            case MOUSE_DOWN, SCROLL, HOTBAR -> pass(Effect.INTERRUPT);
            default -> pass(Effect.NONE);
        };
    }

    private static InputDecision pass(Effect effect) {
        return new InputDecision(false, Deliver.RAW, effect);
    }

    /**
     * A's remap: {@code u = (mouse − rect origin) / scale}; {@code inside} when it lands in the
     * {@code width}×{@code height} screen.
     */
    public static Remap remapPoint(double mouseX, double mouseY, double rectX, double rectY,
                                   double scale, double width, double height) {
        double x = (mouseX - rectX) / scale;
        double y = (mouseY - rectY) / scale;
        return new Remap(x, y, x >= 0.0D && y >= 0.0D && x < width && y < height);
    }
}
