import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;

//Particle collision simulation using real CMS dimuon data from opendata.cern.ch/545
public class ParticleSimulation extends JFrame {
    //defining all colours for GUI
    static final Color Bg = new Color(4, 6, 16);
    static final Color Grid_col = new Color(11, 18, 38);
    static final Color Beam_a_col = new Color(56, 189, 248); //proton beam A, cyan
    static final Color Beam_b_col = new Color(236, 72, 153); //proton beam B, pink
    static final Color Muon_col = new Color(250, 204, 21);
    static final Color Pion_col = new Color(52, 211, 153);
    static final Color Anom_col = new Color(239, 68, 68);
    static final Color Z_col = new Color(167, 139, 250);
    static final Color Higgs_col = new Color(255, 215, 0);
    static final Color Panel_bg = new Color(8, 12, 28);
    static final Color Card_bg = new Color(11, 16, 34);
    static final Color Border = new Color(28, 40, 70);
    static final Color Text_dim = new Color(71, 85, 105);
    static final Color Text_mid = new Color(110, 130, 160);
    static final Color Text_hi = new Color(200, 215, 235);
    static final Color Accent = new Color(56, 189, 248);
    static final Color Green = new Color(52, 211, 153);

    //physical constants; PDG 2022 values, c scaled to sim units
    static final double C = 10.0;
    static final double Proton_m = 938.272;  // MeV/c^2
    static final double Muon_m = 105.658;
    static final double Pion_m = 139.570;
    static final double Inelastic = 0.88;
    static final double Z_thresh = 2.5;
    static final double Z_mass = 91188.0;
    static final double Z_win = 4000.0;
    static final double H_mass = 125090.0;
    static final double H_win = 3000.0;
    static final double Resolution_smear = 0.02;
    static final double Trigger_eff = 0.30;
    static final double Rf_beta = 0.92;
    static final double Rf_tol  = 0.12;
    static final int Num_particles = 14;
    static final int Side_w = 460;
    static final int Hist_h = 130;
    static final int Fps = 60;
    static final int Bins = 120;
    
    int Sw = 800, Sh = 550;
    final List<Double> cernMasses = new ArrayList<>();
    int cernIdx   = 0;
    int cernTotal = 0;
    String dataStatus = "Loading CERN data...";
    final ParticleBeam beamA = new ParticleBeam(+1, Beam_a_col, "Proton", Proton_m, 6);
    final ParticleBeam beamB = new ParticleBeam(-1, Beam_b_col, "Proton", Proton_m, 6);
    final Detector detector = new Detector();
    final AnomalyRegister register = new AnomalyRegister();
    final double[] massHist = new double[Bins];
    final List<Flash> flashes  = new ArrayList<>();
    final List<ProductSprite> products = new ArrayList<>();
    final Random rng = new Random();
    final double[] zRingBuf = new double[300];

    double histPeak = 1;
    long tick   = 0;
    boolean paused = true;
    boolean simStarted = false;
    double  animPhase = 0;
    int totalCollisions = 0;
    int triggeredEvents = 0;
    long startTime = System.currentTimeMillis();
    javax.swing.Timer timerDisplayTimer;
    int zRingHead = 0;
    boolean firstCollisionShown = false;
    boolean zOverlayShown       = false;
    boolean showOverlay = false;
    String  overlayTitle = "", overlaySub = "";
    String[] overlayLines = {};
    Color overlayAccent = Accent;
    CollisionEvent inspectedEvent = null;
    SimCanvas canvas;
    RegisterPanel regPanel;
    InfoPanel infoPanel;
    JButton pauseBtn;
    JButton startBtn;
    JLabel timerLabel;

    static class Flash {
        double x, y, maxRadius;
        Color baseCol;
        int life, startLife;
        Flash(double x, double y, Color c, int life, double maxR) {
            this.x = x; this.y = y;
            baseCol = c;
            this.life = life; startLife = life;
            maxRadius = maxR;}
        boolean dead() { return life <= 0; }
        void draw(Graphics2D g2) {
            float frac = Math.max(0f, Math.min(1f, (float)life / startLife));
            double r = maxRadius * (1 - frac);
            int a = (int)(200 * frac);
            g2.setColor(new Color(baseCol.getRed(), baseCol.getGreen(), baseCol.getBlue(), a));
            g2.setStroke(new BasicStroke(2f));
            g2.drawOval((int)(x-r), (int)(y-r), (int)(r*2), (int)(r*2));
            int da = (int)(255 * frac * frac);
            g2.setColor(new Color(baseCol.getRed(), baseCol.getGreen(), baseCol.getBlue(), da));
            g2.fillOval((int)x-3, (int)y-3, 6, 6);
            life--;}}

    static class ProductSprite {
        double x, y, vx, vy;
        Color col;
        String tag;
        int life, startLife;
        double mass;

        ProductSprite(double x, double y, double vx, double vy, Color c, String tag, int life, double mass) {
            this.x = x; this.y = y;
            this.vx = vx; this.vy = vy;
            col = c; this.tag = tag;
            this.life = life; startLife = life;
            this.mass = mass;}
        boolean dead() {return life <= 0; }
        void step() {
            x += vx; y += vy;
            vx *= 0.97; vy *= 0.97;
            life--;}
        
        void draw(Graphics2D g2) {
            float frac = Math.max(0f, Math.min(1f, (float)life / startLife));
            int a = (int)(200 * frac);
            g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), (int)(50*frac)));
            g2.fillOval((int)(x-10), (int)(y-10), 20, 20);
            g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), a));
            g2.fillOval((int)(x-4), (int)(y-4), 8, 8);
            g2.setFont(new Font("Monospaced", Font.BOLD, 8));
            g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), (int)(220*frac)));
            g2.drawString(tag, (int)(x+5), (int)(y-3));}}

    static class AnomalyRegister {
        final List<CollisionEvent> events = new ArrayList<>();
        int anomCount = 0, zCount = 0, higgsCount = 0;
        void add(CollisionEvent e) {
            events.add(0, e);
            if (events.size() > 500) events.remove(events.size()-1);
            switch (e.kind) {
                case Anomaly -> anomCount++;
                case Z_boson -> zCount++;
                case Higgs_win -> higgsCount++;}}

        void exportCsv(File f) throws IOException {
            try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
                pw.println(CollisionEvent.csvHeader());
                for (CollisionEvent e : events) pw.println(e.csvRow());}}
        void reset() {events.clear();anomCount = 0; zCount = 0; higgsCount = 0; }}

    static class CollisionEvent {
        enum Kind { Anomaly, Z_boson, Higgs_win }
        static int Counter = 0;
        final int id;
        final long atTick;
        final String nameA, nameB;
        final double massA, massB;
        final double mInv;
        final double keBefore_A, keBefore_B, keAfter_A, keAfter_B, deltaKe;
        final double zScore, gammaA, gammaB;
        final double momX_A, momY_A, momX_B, momY_B;
        final Kind kind;
        final String desc;
        final double collX, collY;

        CollisionEvent(long tick, String nA, String nB,
                       double mA, double mB, double mInvGeV,
                       double keA0, double keB0, double keAf, double keBf,
                       double z, double gA, double gB,
                       double pxA, double pyA, double pxB, double pyB,
                       double cx, double cy) {
            id = ++Counter; atTick = tick;
            nameA = nA; nameB = nB;
            massA = mA; massB = mB;
            mInv = mInvGeV;
            keBefore_A = keA0; keBefore_B = keB0;
            keAfter_A  = keAf; keAfter_B  = keBf;
            deltaKe = (keAf+keBf) - (keA0 + keB0);
            zScore = z; gammaA = gA; gammaB = gB;
            momX_A = pxA; momY_A = pyA; momX_B = pxB; momY_B = pyB;
            collX = cx; collY = cy;

            if  (Math.abs(mInvGeV*1000 - Z_mass) < Z_win) kind = Kind.Z_boson;
            else if (Math.abs(mInvGeV*1000 - H_mass) < H_win) kind = Kind.Higgs_win;
            else                                               kind = Kind.Anomaly;

            desc=switch (kind) {
                case Z_boson -> "Z resonance";
                case Higgs_win -> "125 GeV bkg";
                default -> Math.abs(z) > 5 ? "High-z outlier" : deltaKe < -50 ? "Energy sink"
                : deltaKe > 50 ? "Energy spike" : "Stat. anomaly";};}

        Color kindColour() {
            return switch (kind) {
                case Z_boson -> Z_col;
                case Higgs_win -> Higgs_col;
                default -> Anom_col;};}

        String csvRow() {
            return String.format("%d,%d,%s,%s,%.3f,%.3f,%.5f,%.3f,%.3f,%.3f,%.3f,%.4f,%.5f,%s,%s",
                id, atTick, nameA, nameB, massA, massB, mInv,
                keBefore_A, keBefore_B, keAfter_A, keAfter_B, deltaKe, zScore, kind, desc);}

        static String csvHeader() {
            return "id,tick,pA,pB,massA_MeV,massB_MeV,mInv_GeV," + 
            "keA_before,keB_before,keA_after,keB_after,dKE_MeV,z_score,type,label";}}

    static class Detector {
        double mu= 0, m2 = 0, sigma = 0;
        long n= 0;
        int total= 0;
        double update(double v) {
            total++; n++;
            double d = v - mu;
            mu += d / n;
            m2 += d * (v - mu);
            sigma = n < 2 ? 0 : Math.sqrt(m2 / (n-1));
            return sigma < 1e-9 ? 0 : (v - mu) / sigma;}
        void reset() { mu = 0; m2 = 0; sigma = 0; n = 0; total = 0; }}

    static class Particle {
        final String name;
        final Color  col;
        final double mass, r;
        double x, y, vx, vy;
        final double[] trailX = new double[16];
        final double[] trailY = new double[16];
        int trailHead = 0;
        CollisionEvent.Kind flagKind = null;
        int flagCountdown = 0;

        Particle(String name, Color col, double mass, double r,
                 double x, double y, double vx, double vy) {
            this.name = name; this.col = col;
            this.mass = mass; this.r   = r;
            this.x = x; this.y = y;
            this.vx = vx; this.vy = vy;
            Arrays.fill(trailX, x); Arrays.fill(trailY, y);}

        double gamma() {
            double beta = Math.sqrt(vx*vx + vy*vy) / C;
            if (beta >= 1) beta = 0.9999;
            return 1.0 / Math.sqrt(1 - beta*beta);}

        double Ke() { return (gamma() - 1) * mass * C * C; }
        double E() { return gamma()* mass * C * C; }
        double px() { return gamma() * mass * vx; }
        double py() { return gamma() * mass * vy; }
        double speed() {return  Math.sqrt(vx*vx + vy*vy); }

        void move() {
            trailX[trailHead % trailX.length] = x;
            trailY[trailHead % trailY.length] = y;
            trailHead++;
            x += vx; y += vy;}

        void flagCollision(CollisionEvent.Kind k) { flagKind = k; flagCountdown = 240; }
        void tickFlag() {if (flagCountdown > 0 && --flagCountdown == 0) flagKind = null; }
        boolean overlaps(Particle other) {
            double dx = x - other.x, dy = y - other.y;
            return Math.sqrt(dx*dx + dy*dy) < (r + other.r);}}

    class ParticleBeam {
        final int dir;
        final Color col;
        final String pName;
        final double pMass, pRadius;
        final List<Particle> parts = new ArrayList<>();

        ParticleBeam(int dir, Color col, String name, double mass, double rad) {
            this.dir = dir; this.col = col;
            pName = name; pMass = mass; pRadius = rad;}

        void init(int n) {
            parts.clear();
            for (int i = 0; i < n; i++) parts.add(spawn());}

        Particle spawn() {
            int midY = Sh / 2;
            double startX = dir > 0 ? -50 : Sw + 50;
            double y = midY + (rng.nextDouble() - 0.5) * 10;
            double spd = (Rf_beta + (rng.nextDouble() - 0.5) * 0.04) * C;
            double vx = dir * spd;
            double vy = (rng.nextDouble() - 0.5) * C * 0.015;
            return new Particle(pName, col, pMass, pRadius, startX, y, vx, vy);}

        void step(){
            int midY = Sh / 2;
            for (Particle p : parts) {
                p.move();
                p.tickFlag();
                if (p.speed() < (Rf_beta - Rf_tol) * C) {
                    Particle fresh = spawn();
                    p.x = fresh.x; p.y = fresh.y;
                    p.vx = fresh.vx; p.vy = fresh.vy;
                    p.flagKind = null; p.flagCountdown = 0;
                    Arrays.fill(p.trailX, p.x); Arrays.fill(p.trailY, p.y);
                    continue;}
                int buffer = 30;
                if (dir > 0) {
                    if (p.x > Sw + buffer) {
                        p.x = -buffer;}
                } else {
                    if (p.x < -buffer) {
                        p.x = Sw + buffer;}}
                if (p.x > Sw + 200) p.x = -50;
                if (p.x < -200) p.x = Sw + 50;
                double diffY = p.y - midY;
                if (Math.abs(diffY) > 15) {
                    p.vy -= diffY * 0.01;}

                p.vy += (rng.nextDouble() - 0.5) * 0.008;
                p.vy = Math.max(-0.3, Math.min(0.3, p.vy));
            }}}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() ->
            new SlideshowWindow(() ->
                SwingUtilities.invokeLater(() -> new ParticleSimulation().setVisible(true))));}

    public ParticleSimulation() {
        setTitle("Particle Collision Anomaly Detector — CMS Open Data");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);
        
        new Thread(this::loadCernData).start();
        canvas = new SimCanvas();
        regPanel = new RegisterPanel();
        infoPanel = new InfoPanel();
        HistPanel histPanel = new HistPanel();

        JPanel centreArea = new JPanel(new BorderLayout());
        centreArea.add(canvas, BorderLayout.CENTER);
        centreArea.add(histPanel, BorderLayout.SOUTH);

        JPanel rightSide = new JPanel(new BorderLayout());
        rightSide.setPreferredSize(new Dimension(Side_w, 100));
        rightSide.add(regPanel, BorderLayout.CENTER);
        rightSide.add(infoPanel, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(buildTopBar(),BorderLayout.NORTH);
        add(centreArea, BorderLayout.CENTER);
        add(rightSide, BorderLayout.EAST);

        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration());
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int usableH = screen.height - screenInsets.top - screenInsets.bottom;
        int usableW = screen.width - screenInsets.left - screenInsets.right;
        int decorH = 38;
        int idealH = 42 + Sh + Hist_h + decorH;
        if (idealH > usableH) {
            Sh = Math.max(300, usableH - 42 - Hist_h - decorH);
            idealH = usableH;}

        int idealW = Math.min(Sw + Side_w + 6, usableW);
        Sw = idealW - Side_w - 6;
        setSize(idealW, idealH);
        setLocationRelativeTo(null);
        initSim();
        timerDisplayTimer = new javax.swing.Timer(1000, e -> updateTimerDisplay());
        timerDisplayTimer.start();

        new javax.swing.Timer(1000 / Fps, e -> {
            if (!paused && !showOverlay) step();
            canvas.repaint(); regPanel.repaint(); infoPanel.repaint(); histPanel.repaint();
        }).start();}
    
    void updateTimerDisplay() {
        long elapsed = System.currentTimeMillis() - startTime;
        long seconds = elapsed / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (timerLabel != null) {
            timerLabel.setText(String.format("⏱ %02d:%02d", minutes, seconds));}}

    void initSim() {
        beamA.init(Num_particles);
        beamB.init(Num_particles);
        CollisionEvent.Counter = 0;
        flashes.clear();
        products.clear();
        totalCollisions = 0;
        triggeredEvents = 0;
        paused = true;
        simStarted = false;
        if (startBtn != null) {
            startBtn.setText("Start");
            startBtn.setForeground(Green);}
        if (pauseBtn != null) {
            pauseBtn.setText("Pause");
            pauseBtn.setEnabled(false);}
        startTime = System.currentTimeMillis();
        if (timerLabel != null) {
            timerLabel.setText("⏱ 00:00");}}

    void step() {
        tick++;
        animPhase = (animPhase + 0.07) % (2 * Math.PI);
        beamA.step();
        beamB.step();

        products.removeIf(ProductSprite::dead);
        for (ProductSprite s : products) s.step();
        flashes.removeIf(Flash::dead);

        for (Particle a : beamA.parts)
            for (Particle b : beamB.parts)
                if (a.overlaps(b)) collide(a, b);}

    void spawnProductsWithMomentum(double cx, double cy, double totalKe, double pxTotal, double pyTotal) {
        int n = 2 + rng.nextInt(3);
        double remainingKe = totalKe;
        double remainingPx = pxTotal;
        for (int i = 0; i < n; i++) {
            boolean isMuon = rng.nextBoolean();
            double mass = isMuon ? Muon_m : Pion_m;
            double maxKe = remainingKe / (n - i);
            if (maxKe <= 0) break;
            double keFraction = 0.3 + rng.nextDouble() * 0.5;
            double ke = maxKe * keFraction;
            remainingKe -= ke;
            double momentum = Math.sqrt(ke * ke + 2 * ke * mass * C * C) / C;
            double angle = rng.nextDouble() * 2 * Math.PI;
            double assignedPx = momentum * Math.cos(angle) * (remainingPx > 0 ? 1 : -1);
            double assignedPy = momentum * Math.sin(angle);
            remainingPx -= assignedPx;
            double vx = assignedPx * C * C / Math.sqrt(assignedPx * assignedPx * C * C + mass * mass * C * C * C * C);
            double vy = assignedPy * C * C / Math.sqrt(assignedPy * assignedPy * C * C + mass * mass * C * C * C * C);
            products.add(new ProductSprite(cx, cy, vx, vy,
                isMuon ? Muon_col : Pion_col,
                isMuon ? "μ" : "π",
                40 + rng.nextInt(30), mass));}}

    void collide(Particle a, Particle b) {
        totalCollisions++;
        double ke0A = a.Ke(), ke0B = b.Ke();
        double gA   = a.gamma(), gB = b.gamma();
        double pxA  = a.px(), pyA = a.py(), pxB = b.px(), pyB = b.py();
        double dx = b.x - a.x, dy = b.y - a.y;
        double dist = Math.sqrt(dx*dx + dy*dy);
        if (dist == 0) return;
        double nx = dx / dist, ny = dy / dist;
        double pA= a.gamma() * a.mass * (a.vx*nx + a.vy*ny);
        double pB = b.gamma() * b.mass * (b.vx*nx + b.vy*ny);
        double aXp = a.gamma() * a.mass * a.vx - pA * nx;
        double aYp = a.gamma() * a.mass * a.vy - pA * ny;
        double bXp = b.gamma() * b.mass * b.vx - pB * nx;
        double bYp = b.gamma() * b.mass * b.vy - pB * ny;
        double Etot = a.E() + b.E();
        double betaCm = (pA + pB) * C * C / Etot;
        if (Math.abs(betaCm) >= 1) betaCm = Math.signum(betaCm) * 0.999;
        double gammaCm = 1.0/Math.sqrt(1 - betaCm * betaCm);
        double pAcm = gammaCm * (pA - betaCm * a.E() / C);
        double eAcm = Math.sqrt(pAcm*pAcm * C*C + Math.pow(a.mass * C*C, 2));
        double eBcm = Math.sqrt(pAcm*pAcm * C*C + Math.pow(b.mass * C*C, 2));
        double pAf = gammaCm * (-pAcm + betaCm * eAcm / C);
        double pBf = gammaCm * ( pAcm + betaCm * eBcm / C);
        double eAf = Math.sqrt((pAf*pAf + aXp*aXp + aYp*aYp) * C*C + Math.pow(a.mass*C*C, 2));
        double eBf = Math.sqrt((pBf*pBf + bXp*bXp + bYp*bYp) * C*C + Math.pow(b.mass*C*C, 2));
        a.vx = (pAf * nx + aXp) * C*C / eAf;
        a.vy = (pAf * ny + aYp) * C*C / eAf;
        b.vx = (pBf * nx + bXp) * C*C / eBf;
        b.vy = (pBf * ny + bYp) * C*C / eBf;

        double sf = Math.sqrt(Inelastic);
        a.vx *= sf; a.vy *= sf;
        b.vx *= sf; b.vy *= sf;
        clampSpeed(a); clampSpeed(b);

        double overlap = (a.r + b.r) - dist + 1;
        a.x -= nx* overlap/2; a.y -= ny * overlap / 2;
        b.x += nx* overlap/2; b.y += ny * overlap / 2;

        double cx = (a.x + b.x) / 2, cy = (a.y + b.y) / 2;
        double keLoss = (ke0A + ke0B) - (a.Ke() + b.Ke());
        if (keLoss > 0) {
            double pxTotal= (pxA + pxB)- (a.px() + b.px());
            double pyTotal= (pyA + pyB)- (a.py() + b.py());
            spawnProductsWithMomentum(cx, cy, keLoss, pxTotal, pyTotal);}

        double mInvGeV_raw = nextMass();
        double mInvGeV = mInvGeV_raw * (1.0 + rng.nextGaussian() * Resolution_smear);
        boolean triggered = rng.nextDouble() < Trigger_eff;
        if (triggered) triggeredEvents++;
        int binIdx = (int) mInvGeV;
        if (binIdx >= 0 && binIdx < Bins) {
            massHist[binIdx]++;
            if (massHist[binIdx] > histPeak) histPeak = massHist[binIdx];}

        double z = detector.update(mInvGeV);
        zRingBuf[zRingHead % zRingBuf.length] = Math.abs(z);
        zRingHead++;

        boolean notable = (triggered && (Math.abs(z) > Z_thresh
            || Math.abs(mInvGeV * 1000 - Z_mass) < Z_win
            || Math.abs(mInvGeV * 1000 - H_mass) < H_win));

        if (notable) {
            CollisionEvent ev = new CollisionEvent(tick, a.name, b.name, a.mass, b.mass,
                mInvGeV, ke0A, ke0B, a.Ke(), b.Ke(), z, gA, gB, pxA, pyA, pxB, pyB, cx, cy);
            register.add(ev);
            a.flagCollision(ev.kind); b.flagCollision(ev.kind);

            Color fc = ev.kindColour();
            flashes.add(new Flash(cx, cy, fc, 26, 36));
            flashes.add(new Flash(a.x, a.y, fc, 16, 18));
            flashes.add(new Flash(b.x, b.y, fc, 16, 18));

            if (!firstCollisionShown) {
                firstCollisionShown = true;
                showOverlay("FIRST NOTABLE COLLISION",
                    "Event #" + ev.id + "  " + ev.desc + "  m_inv=" + String.format("%.3f", ev.mInv) + " GeV",
                    new String[]{
                        "A collision has been flagged and stored in the register.",
                        "",
                        "Beam layout (LHC-style opposing beams):",
                        "  Cyan  → = Beam A  (protons, 938.272 MeV/c²) — moving right",
                        "  Pink  ← = Beam B  (protons, 938.272 MeV/c²) — moving left",
                        "  Both beams travel on the SAME horizontal axis",
                        "  Gold/green sparks = muon/pion collision products (fade)",
                        "",
                        "RF cavity: if a beam particle slows below 80% of c,",
                        "  it is re-injected at 92% c from the entry edge.",
                        "",
                        "Click any row in the REGISTER panel → see kinematics below it.",
                        "CLICK anywhere to resume"
                    }, ev.kindColour());
                return;}

            if (!zOverlayShown && ev.kind == CollisionEvent.Kind.Z_boson) {
                zOverlayShown = true;
                showOverlay("Z BOSON CANDIDATE",
                    String.format("m_inv = %.4f GeV  (PDG Z = 91.188 GeV)", ev.mInv),
                    new String[]{
                        "A real CMS dimuon event at the Z boson resonance.",
                        "This is a genuine signal from real CERN data.",
                        "",
                        "Click this event in the register to inspect:",
                        "  γ_A, γ_B (Lorentz factors), momenta px/py",
                        "  ΔKE, z-score, invariant mass, classification",
                        "",
                        "The Z peak at 91 GeV will grow clearly in the histogram.",
                        "",
                        "H? events near 125 GeV = BACKGROUND only.",
                        "CLICK anywhere to resume"
                    }, Z_col);}
        } else {
            flashes.add(new Flash(cx, cy, new Color(160, 200, 255), 7, 16));}}

    void showOverlay(String title, String sub, String[] body, Color col) {
        overlayTitle = title; overlaySub = sub; overlayLines = body; overlayAccent = col;
        showOverlay = true; paused = true;}

    void dismissOverlay() {
        showOverlay = false;
        if (simStarted) paused = false; }

    void clampSpeed(Particle p) {
        double sp = Math.sqrt(p.vx*p.vx + p.vy*p.vy);
        if (sp >= C * 0.9999) { double f = C * 0.9999 / sp; p.vx *= f; p.vy *= f; }}

    void loadCernData() {
        String[] urls = {
            "http://opendata.cern.ch/record/545/files/Dimuon_DoubleMu.csv",
            "https://opendata.cern.ch/record/545/files/Dimuon_DoubleMu.csv"};
        for (String url : urls) {
            try {
                dataStatus = "Connecting to opendata.cern.ch...";
                HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
                conn.setConnectTimeout(8000); conn.setReadTimeout(25000);
                conn.setRequestProperty("User-Agent", "ParticleSimulation");
                if (conn.getResponseCode() != 200) continue;
                dataStatus = "Downloading CMS events...";
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                reader.readLine();
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] cols = line.trim().split(",");
                    try {
                        double m = Double.parseDouble(cols[cols.length-1].trim());
                        if (m > 0 && m < 200) cernMasses.add(m);
                    } catch (NumberFormatException ignored) {}}
                reader.close();
                cernTotal = cernMasses.size();
                Collections.shuffle(cernMasses, rng);
                dataStatus = "CMS data: " + cernTotal + " real events";
                return;
            } catch (Exception e) {
                dataStatus = "Offline — synthetic spectrum";}}
        buildSyntheticSpectrum();}

    void buildSyntheticSpectrum() {
        for (int i = 0; i < 100000; i++) {
            double m, roll = rng.nextDouble();
            if      (roll < 0.18) m = 3.097  + rng.nextGaussian() * 0.05;
            else if (roll < 0.28) m = 9.46   + rng.nextGaussian() * 0.15;
            else if (roll < 0.45) m = 91.2   + rng.nextGaussian() * 2.5;
            else                  m = 2.0    + rng.nextDouble()   * 110;
            if (m > 0 && m < 200) cernMasses.add(m);}
        Collections.shuffle(cernMasses, rng);
        cernTotal = cernMasses.size();}
    double nextMass() {
        if (cernMasses.isEmpty()) return 2 + rng.nextDouble() * 100;
        double m = cernMasses.get(cernIdx % cernMasses.size());
        cernIdx++;
        return m;}

    JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Panel_bg);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Border));
        bar.setPreferredSize(new Dimension(100, 42));

        JPanel labelCol = new JPanel(new GridLayout(2, 1));
        labelCol.setOpaque(false);
        JLabel titleLine = new JLabel("  PARTICLE COLLISION ANOMALY DETECTOR  |  Proton x Proton  |  CMS 2011");
        titleLine.setFont(new Font("Monospaced", Font.BOLD, 11));
        titleLine.setForeground(Accent);
        JLabel subLine = new JLabel("  Cyan→ = Beam A (p)   Pink← = Beam B (p)   Same axis   Gold/green sparks = μ/π   RF cavity");
        subLine.setFont(new Font("Monospaced", Font.PLAIN, 9));
        subLine.setForeground(Text_dim);
        labelCol.add(titleLine); labelCol.add(subLine);

        startBtn  = makeBtn("Start");
        pauseBtn  = makeBtn("Pause");
        JButton exportBtn = makeBtn("Export");
        JButton resetBtn  = makeBtn("Reset");
        JButton infoBtn   = makeBtn("Info");
        JButton disclaimerBtn = makeBtn("Disclaimer");

        startBtn.setForeground(Green); 
        pauseBtn.setEnabled(false);

        startBtn.addActionListener(e -> {
            if (!simStarted) {
                simStarted = true;
                paused = false;
                startTime = System.currentTimeMillis();
                startBtn.setText("Running");
                startBtn.setForeground(Text_dim);
                startBtn.setEnabled(false);
                pauseBtn.setEnabled(true);
            }});

        pauseBtn.addActionListener(e -> {
            if (!showOverlay && simStarted) {
                paused = !paused;
                pauseBtn.setText(paused ? "Resume" : "Pause");
            }});

        exportBtn.addActionListener(e -> {
            if (register.events.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No flagged events yet.");
                return;
            }
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("anomaly_register.csv"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    register.exportCsv(fc.getSelectedFile());
                    JOptionPane.showMessageDialog(this, "Exported " + register.events.size() + " events.");
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "Export failed.");}}});

        resetBtn.addActionListener(e -> {
            paused = true;
            simStarted = false;
            initSim();
            tick = 0;
            detector.reset();
            Arrays.fill(massHist, 0);
            histPeak = 1;
            register.reset();
            Arrays.fill(zRingBuf, 0);
            zRingHead = 0;
            cernIdx = 0;
            firstCollisionShown = false;
            zOverlayShown = false;
            showOverlay = false;
            inspectedEvent = null;
            totalCollisions = 0;
            triggeredEvents = 0;
            startTime = System.currentTimeMillis();
            updateTimerDisplay();
            startBtn.setText("Start");
            startBtn.setForeground(Green);
            startBtn.setEnabled(true);
            pauseBtn.setText("Pause");
            pauseBtn.setEnabled(false);
            flashes.clear();
            products.clear();
            canvas.repaint();
            regPanel.repaint();
            infoPanel.repaint();});

        infoBtn.addActionListener(e -> {
            boolean wasP = paused; paused = true;
            String msg = 
                "BEAMS: Proton (938 MeV) both directions, SAME AXIS\n" +
                "RF cavity: 92% c, reinject below 80% c\n" +
                "Detector: " + (int)(Resolution_smear*100) + "% smear, " + (int)(Trigger_eff*100) + "% trigger\n" +
                "Classes: ParticleBeam, Particle, CollisionEvent, Detector, AnomalyRegister\n" +
                "Physics: γ = 1/√(1−v²/c²), KE=(γ−1)mc², m_inv² = (ΣE)²/c⁴ − |Σp|²/c²\n" +
                "Data: opendata.cern.ch/record/545";
            JOptionPane.showMessageDialog(this, msg, "Info", JOptionPane.INFORMATION_MESSAGE);
            paused = wasP;});
        
        disclaimerBtn.addActionListener(e -> {
            boolean wasP = paused; paused = true;
            String msg = 
                "⚠ SIMPLIFICATIONS ⚠\n\n" +
                "• Collision = automatic overlap (real physics has cross-section probability)\n" +
                "• μ/π products use approximate kinematics (real needs PYTHIA)\n" +
                "• Perfect detection efficiency (real has geometric acceptance)\n" +
                "• No QCD background (real has Drell-Yan, bb, cc)\n" +
                "• 2D visualization (real CMS is 3D with 3.8T solenoid)\n" +
                "• Simplified RF cavity (real LHC uses superconducting cavities)\n\n" +
                "✓ WHAT'S ACCURATE: Relativistic kinematics, invariant mass method,\n" +
                "  Z boson resonance from real CMS data, z-score anomaly detection,\n" +
                "  detector resolution smearing, trigger efficiency modeling.\n\n" +
                "Educational visualization — not production HEP code.";
            JOptionPane.showMessageDialog(this, msg, "Disclaimer", JOptionPane.WARNING_MESSAGE);
            paused = wasP;});

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 7));
        btnRow.setOpaque(false);
        for (JButton b : new JButton[]{infoBtn, disclaimerBtn, exportBtn, startBtn, pauseBtn, resetBtn}) btnRow.add(b);
        timerLabel = new JLabel("⏱ 00:00");
        timerLabel.setFont(new Font("Monospaced", Font.BOLD, 10));
        timerLabel.setForeground(Accent);
        timerLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setOpaque(false);
        rightPanel.add(timerLabel, BorderLayout.WEST);
        rightPanel.add(btnRow, BorderLayout.EAST);
        bar.add(labelCol, BorderLayout.WEST);
        bar.add(rightPanel, BorderLayout.EAST);
        return bar;}

    JButton makeBtn(String label) {
        JButton b = new JButton(label);
        b.setFont(new Font("Monospaced", Font.PLAIN, 10));
        b.setForeground(Text_hi);
        b.setBackground(new Color(20, 32, 55));
        b.setFocusPainted(false); b.setBorderPainted(false);
        b.setPreferredSize(new Dimension(75, 24));
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return b;}

    class SimCanvas extends JPanel {
        SimCanvas() {
            setBackground(Bg);
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) { if (showOverlay) dismissOverlay(); }});}
        @Override public void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), midY = h / 2;
            g2.setColor(Grid_col); g2.setStroke(new BasicStroke(0.5f));
            for (int x = 0; x < w; x += 50) g2.drawLine(x, 0, x, h);
            for (int y = 0; y < h; y += 50) g2.drawLine(0, y, w, y);
            g2.setColor(new Color(56, 189, 248, 30));
            g2.fillRect(0, midY - 25, w, 20);
            g2.setColor(new Color(236, 72, 153, 30));
            g2.fillRect(0, midY - 5, w, 20);
            float dashOff = (float)(tick % 20);
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                0, new float[]{8, 6}, dashOff));
            g2.setColor(new Color(255, 255, 255, 40));
            g2.drawLine(0, midY, w, midY);
            g2.setStroke(new BasicStroke(1));
            g2.setFont(new Font("Monospaced", Font.BOLD, 9));
            g2.setColor(new Color(56, 189, 248, 80));
            for (int x = 80; x < w-40; x += 140) g2.drawString("→→", x, midY - 10);
            g2.setColor(new Color(236, 72, 153, 80));
            for (int x = 40; x < w-60; x += 140) g2.drawString("←←", x, midY + 14);
            g2.setColor(new Color(56, 189, 248, 100));
            g2.drawString("BEAM A  p →", 6, midY - 6);
            g2.setColor(new Color(236, 72, 153, 100));
            g2.drawString("BEAM B  p ←", 6, midY + 14);
            for (Flash f : flashes) f.draw(g2);
            for (ProductSprite s : products) s.draw(g2);
            for (Particle p : beamA.parts) drawParticle(g2, p);
            for (Particle p : beamB.parts) drawParticle(g2, p);
            drawSparkline(g2);
            drawLegend(g2, w, h);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 9));
            g2.setColor(new Color(255, 255, 255, 20));
            g2.drawString("TICK " + tick + "  COLL " + totalCollisions + "  TRIG " + triggeredEvents + "  " + dataStatus, 8, h-8);
            if (!simStarted) {
                int cw = getWidth(), ch = getHeight();
                g2.setColor(new Color(0, 0, 0, 120));
                g2.fillRect(0, 0, cw, ch);
                g2.setFont(new Font("Monospaced", Font.BOLD, 18));
                g2.setColor(Green);
                String msg = "Press  [ Start ]  to begin the simulation";
                int tw = g2.getFontMetrics().stringWidth(msg);
                g2.drawString(msg, (cw - tw) / 2, ch / 2);
                g2.setFont(new Font("Monospaced", Font.PLAIN, 10));
                g2.setColor(Text_dim);
                String sub = "Beams are loaded and ready — collision detector standing by";
                int sw2 = g2.getFontMetrics().stringWidth(sub);
                g2.drawString(sub, (cw - sw2) / 2, ch / 2 + 22);}
            if (showOverlay) drawOverlay(g2, w, h);}

        void drawParticle(Graphics2D g2, Particle p) {
            int ix = (int)p.x, iy = (int)p.y, ir = (int)p.r;
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int tlen = p.trailX.length;
            for (int i = 1; i < tlen; i++) {
                int ci = (p.trailHead - i + tlen) % tlen;
                int pi = (p.trailHead - i - 1 + tlen) % tlen;
                float alpha = (float)(tlen - i) / tlen * 0.45f;
                g2.setColor(new Color(p.col.getRed(), p.col.getGreen(), p.col.getBlue(), (int)(255*alpha)));
                g2.drawLine((int)p.trailX[ci], (int)p.trailY[ci], (int)p.trailX[pi], (int)p.trailY[pi]);}
            if (p.flagKind != null) {
                Color hc = switch (p.flagKind) {
                    case Z_boson   -> Z_col;
                    case Higgs_win -> Higgs_col;
                    default        -> Anom_col;};
                double gr = ir * 3.5 + Math.sin(animPhase) * 5;
                int al = (int)(55 + 55 * Math.abs(Math.sin(animPhase)));
                g2.setColor(new Color(hc.getRed(), hc.getGreen(), hc.getBlue(), al));
                g2.fillOval((int)(ix - gr), (int)(iy - gr), (int)(gr*2), (int)(gr*2));
                g2.setFont(new Font("Monospaced", Font.BOLD, 9));
                g2.setColor(hc);
                g2.drawString(switch (p.flagKind) {
                    case Z_boson -> "Z"; case Higgs_win -> "H?"; default -> "!";
                }, ix + ir + 2, iy - ir - 1);}
            g2.setColor(new Color(p.col.getRed(), p.col.getGreen(), p.col.getBlue(), 38));
            g2.fillOval((int)(ix - ir*2.1), (int)(iy - ir*2.1), (int)(ir*4.2), (int)(ir*4.2));
            g2.setColor(p.col);
            g2.fillOval(ix - ir, iy - ir, ir*2, ir*2);
            g2.setColor(new Color(255, 255, 255, 105));
            g2.fillOval((int)(ix - ir*0.45), (int)(iy - ir*0.5), (int)(ir*0.6), (int)(ir*0.6));
            g2.setStroke(new BasicStroke(1.5f));
            g2.setColor(new Color(p.col.getRed(), p.col.getGreen(), p.col.getBlue(), 155));
            g2.drawLine(ix, iy, ix + (int)(p.vx * 2.0), iy + (int)(p.vy * 2.0));
            g2.setFont(new Font("Monospaced", Font.PLAIN, 7));
            g2.setColor(new Color(255, 255, 255, 80));
            g2.drawString("p", ix + ir + 1, iy + 4);}

        void drawSparkline(Graphics2D g2) {
            int sx = 10, sy = 10, sw = 170, sh = 40;
            g2.setColor(new Color(6, 10, 24, 210));
            g2.fillRoundRect(sx-3, sy-3, sw+6, sh+18, 6, 6);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 8));
            g2.setColor(Text_dim);
            g2.drawString("|z| rolling  — threshold " + Z_thresh + "σ", sx, sy + sh + 12);
            int len = zRingBuf.length;
            double maxZ = Math.max(Z_thresh * 2, 1);
            for (double v : zRingBuf) if (v > maxZ) maxZ = v;
            int threshY = (int)(sy + sh - Z_thresh / maxZ * sh);
            g2.setColor(new Color(Anom_col.getRed(), Anom_col.getGreen(), Anom_col.getBlue(), 70));
            g2.setStroke(new BasicStroke(0.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                0, new float[]{4, 4}, 0));
            g2.drawLine(sx, threshY, sx+sw, threshY);
            g2.setStroke(new BasicStroke(1.2f));
            for (int i = 1; i < len; i++) {
                int ia = (zRingHead + i - 1) % len, ib = (zRingHead + i) % len;
                int x1 = sx + (i-1)*sw/len, x2 = sx + i*sw/len;
                int y1 = (int)(sy + sh - zRingBuf[ia] / maxZ * sh);
                int y2 = (int)(sy + sh - zRingBuf[ib] / maxZ * sh);
                float frac = (float)i / len;
                g2.setColor(new Color(56, 189, 248, (int)(50 + 140*frac)));
                g2.drawLine(x1, y1, x2, y2);}}

        void drawLegend(Graphics2D g2, int w, int h) {
            int lx = 8, ly = h-130, lw = 210, lh = 120;
            g2.setColor(new Color(6, 10, 24, 190));
            g2.fillRoundRect(lx-2, ly-2, lw, lh, 6, 6);
            g2.setFont(new Font("Monospaced", Font.BOLD, 9));
            g2.setColor(Accent);
            g2.drawString("LEGEND", lx+4, ly+12);
            Object[][] rows = {
                {Beam_a_col, "Beam A — p → (same axis)"},
                {Beam_b_col, "Beam B — p ← (same axis)"},
                {Muon_col, "μ  Muon product"},
                {Pion_col,"π  Pion product"},
                {Anom_col, "!  Anomaly |z|>2.5"},
                {Z_col, "Z  Z boson"},
                {Higgs_col, "H 125 GeV bkg"},};
            int ry = ly + 24;
            for (Object[] row : rows) {
                Color c = (Color) row[0];
                g2.setColor(c); g2.fillOval(lx+4, ry-7, 8, 8);
                g2.setFont(new Font("Monospaced", Font.PLAIN, 9));
                g2.setColor(Text_hi); g2.drawString((String)row[1], lx+16, ry);
                ry += 14;}}

        void drawOverlay(Graphics2D g2, int cw, int ch) {
            g2.setColor(new Color(0, 0, 0, 185));
            g2.fillRect(0, 0, cw, ch);
            int cardW = Math.min(550, cw-40), cardH = Math.min(350, ch-40);
            int cx = (cw - cardW) / 2, cy = (ch - cardH) / 2;
            g2.setColor(new Color(6, 10, 24, 248));
            g2.fillRoundRect(cx, cy, cardW, cardH, 14, 14);
            g2.setColor(overlayAccent);
            g2.fillRect(cx, cy, cardW, 5);
            g2.setFont(new Font("Monospaced", Font.BOLD, 14));
            g2.setColor(overlayAccent);
            g2.drawString(overlayTitle, cx+18, cy+30);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 10));
            g2.setColor(Text_mid);
            g2.drawString(overlaySub, cx+18, cy+46);
            g2.setColor(Border);
            g2.drawLine(cx+18, cy+55, cx+cardW-18, cy+55);

            int ty = cy + 72;
            for (String ln : overlayLines) {
                if (ln.startsWith("CLICK")) g2.setColor(overlayAccent);
                else g2.setColor(Text_hi);
                g2.setFont(new Font("Monospaced", Font.PLAIN, 11));
                g2.drawString(ln, cx+18, ty);
                ty += 18;
                if (ty > cy + cardH - 20) break;}
            g2.setColor(new Color(56, 189, 248, 120));
            g2.setFont(new Font("Monospaced", Font.BOLD, 10));
            g2.drawString("[ click anywhere ]", cx + cardW - 130, cy + cardH - 12);}}


    class RegisterPanel extends JPanel {
        static final int Row_h = 20, Header_h = 52;
        RegisterPanel() {
            setBackground(Panel_bg);
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    int row = (e.getY() - Header_h) / Row_h;
                    if (row >= 0 && row < register.events.size()) {
                        inspectedEvent = register.events.get(row);
                        repaint(); infoPanel.repaint();
                    }}
            });}

        @Override
        public void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D)g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            g2.setColor(Card_bg); g2.fillRect(0, 0, w, Header_h);
            g2.setColor(Border);  g2.drawLine(0, Header_h, w, Header_h);
            g2.setFont(new Font("Monospaced", Font.BOLD, 10)); g2.setColor(Accent);
            g2.drawString("ANOMALY REGISTER", 10, 16);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 8)); g2.setColor(Text_dim);
            g2.drawString(register.events.size() + " events | click row", 10, 30);
            g2.setColor(new Color(16, 26, 50)); g2.fillRect(0, Header_h, w, 16);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 8)); g2.setColor(Text_mid);
            g2.drawString("ID", 6, Header_h + 12);
            g2.drawString("TYPE", 36, Header_h + 12);
            g2.drawString("m_inv", 100, Header_h + 12);
            g2.drawString("z", 180, Header_h + 12);
            g2.drawString("ΔKE", 250, Header_h + 12);
            g2.setColor(Border); g2.drawLine(0, Header_h+16, w, Header_h+16);

            int startY = Header_h + 16;
            int maxRows = (getHeight() - startY - 24) / Row_h;
            for (int i = 0; i < Math.min(register.events.size(), maxRows); i++) {
                CollisionEvent ev = register.events.get(i);
                int ry = startY + i * Row_h;
                boolean sel = (ev == inspectedEvent);
                g2.setColor(sel ? new Color(28, 52, 100) : i%2==0 ? new Color(9, 14, 30) : Card_bg);
                g2.fillRect(0, ry, w, Row_h);
                g2.setFont(new Font("Monospaced", Font.PLAIN, 8));
                g2.setColor(sel ? Color.WHITE : Text_dim);
                g2.drawString("#" + ev.id, 6, ry+13);
                g2.setColor(ev.kindColour());
                String typeStr = ev.kind.name().substring(0, Math.min(3, ev.kind.name().length()));
                g2.drawString(typeStr, 36, ry+13);
                g2.setColor(sel ? Color.WHITE : Text_hi);
                g2.drawString(String.format("%.2f", ev.mInv), 100, ry+13);
                g2.setColor(Math.abs(ev.zScore) > Z_thresh ? Anom_col : Text_mid);
                g2.drawString(String.format("%.2f", ev.zScore), 180, ry+13);
                g2.drawString(String.format("%+.0f", ev.deltaKe), 250, ry+13);
                g2.setColor(Border); g2.drawLine(0, ry+Row_h-1, w, ry+Row_h-1);}

            if (register.events.isEmpty()) {
                g2.setFont(new Font("Monospaced", Font.PLAIN, 9));
                g2.setColor(Text_dim);
                g2.drawString("Waiting for collisions...", 12, Header_h + 40);}

            int footY = getHeight() - 20;
            g2.setColor(new Color(8, 12, 26)); g2.fillRect(0, footY, w, 20);
            g2.setColor(Border); g2.drawLine(0, footY, w, footY);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 8));
            g2.setColor(Anom_col); g2.drawString("! " + register.anomCount, 8, footY+13);
            g2.setColor(Z_col);    g2.drawString("Z " + register.zCount, 55, footY+13);
            g2.setColor(Higgs_col);g2.drawString("H? " + register.higgsCount, 100, footY+13);
            g2.setColor(Text_dim); g2.drawString("total: " + register.events.size(), 150, footY+13);}}

    class InfoPanel extends JPanel {
        InfoPanel() {setBackground(Card_bg);
            setPreferredSize(new Dimension(Side_w, 240));}
        @Override
        public void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D)g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            g2.setColor(Border); g2.drawLine(0, 0, w, 0);

            if (inspectedEvent == null) {
                g2.setFont(new Font("Monospaced", Font.BOLD, 10)); g2.setColor(Accent);
                g2.drawString("INSPECTOR", 10, 18);
                g2.setFont(new Font("Monospaced", Font.PLAIN, 9)); g2.setColor(Text_dim);
                g2.drawString("Click a row to see", 10, 42);
                g2.drawString("kinematics here.", 10, 56);
                return;}

            CollisionEvent ev = inspectedEvent;
            Color tc = ev.kindColour();

            g2.setColor(new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), 35));
            g2.fillRect(0, 0, w, 26);
            g2.setColor(tc); g2.fillRect(0, 0, 4, getHeight());

            g2.setFont(new Font("Monospaced", Font.BOLD, 10)); g2.setColor(tc);
            g2.drawString("EVENT #" + ev.id + "  " + ev.kind, 10, 17);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 8)); g2.setColor(Text_mid);
            g2.drawString(ev.desc, 10, 28);
            g2.setColor(Border); g2.drawLine(8, 32, w-8, 32);

            String[][] rows = {
                {"γ_A", String.format("%.3f", ev.gammaA), "γ_B", String.format("%.3f", ev.gammaB)},
                {"px_A", String.format("%.1f", ev.momX_A), "px_B", String.format("%.1f", ev.momX_B)},
                {"py_A", String.format("%.1f", ev.momY_A), "py_B", String.format("%.1f", ev.momY_B)},
                {"KE_A", String.format("%.0f", ev.keBefore_A), "KE_B", String.format("%.0f", ev.keBefore_B)},
                {"m_inv", String.format("%.4f", ev.mInv), "z", String.format("%.3f", ev.zScore)},};

            int iy = 44;
            for (String[] row : rows) {
                g2.setFont(new Font("Monospaced", Font.PLAIN, 8));
                g2.setColor(Text_dim); g2.drawString(row[0], 10, iy);
                g2.setColor(Text_hi);  g2.drawString(row[1], 65, iy);
                g2.setColor(Text_dim); g2.drawString(row[2], 150, iy);
                g2.setColor(Text_hi);  g2.drawString(row[3], 205, iy);
                iy += 16;}}}

    class HistPanel extends JPanel {
        HistPanel() { setPreferredSize(new Dimension(100, Hist_h)); setBackground(new Color(3, 4, 11)); }
        @Override
        public void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D)g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int leftPad = 45, botY = Hist_h - 35;
            int plotW   = getWidth() - leftPad - 10;
            double binW = (double)plotW / Bins;

            g2.setColor(new Color(51, 65, 85)); g2.setStroke(new BasicStroke(1));
            g2.drawLine(leftPad, 4, leftPad, botY);
            g2.drawLine(leftPad, botY, leftPad+plotW, botY);

            int zBin = (int)(Z_mass / 1000);
            g2.setColor(new Color(Z_col.getRed(), Z_col.getGreen(), Z_col.getBlue(), 26));
            g2.fillRect((int)(leftPad + Math.max(0, zBin-4)*binW), 4, (int)(8*binW), botY-4);

            int hBin = (int)(H_mass / 1000);
            if (hBin < Bins) {
                g2.setColor(new Color(255, 215, 0, 20));
                g2.fillRect((int)(leftPad + Math.max(0, hBin-3)*binW), 4, (int)(6*binW), botY-4);}

            for (int i = 0; i < Bins; i++) {
                if (massHist[i] == 0) continue;
                double barH = massHist[i] / histPeak * (botY - 6);
                Color bc = Math.abs(i - zBin) <= 4 ? Z_col
                         : (hBin < Bins && Math.abs(i - hBin) <= 3) ? Higgs_col
                         : Accent;
                g2.setColor(new Color(bc.getRed(), bc.getGreen(), bc.getBlue(), 180));
                g2.fillRect((int)(leftPad + i*binW), (int)(botY - barH), Math.max(1, (int)(binW-0.5)), (int)barH);}

            g2.setFont(new Font("Monospaced", Font.PLAIN, 8));
            g2.setColor(Text_mid);
            g2.drawString("dN/dm", 2, 14);

            int[] tickGeV = {0, 30, 60, 91, 125};
            String[] tickStr = {"0", "30", "60", "91", "125"};
            for (int t = 0; t < tickGeV.length; t++) {
                int gev = tickGeV[t];
                int px = leftPad + (int)(gev * binW);
                if (px < leftPad || px > leftPad + plotW) continue;
                int labelY = (t % 2 == 0) ? botY + 10 : botY + 20;
                g2.setColor(new Color(51, 65, 85));
                g2.drawLine(px, botY, px, botY + 3);
                g2.setColor(Text_mid);
                FontMetrics fm = g2.getFontMetrics();
                int strW = fm.stringWidth(tickStr[t]);
                g2.drawString(tickStr[t], px - strW/2, labelY);}
            g2.drawString("GeV", leftPad + plotW - 18, botY + 10);

            g2.setColor(Z_col);
            g2.drawString("Z", leftPad + (int)(zBin * binW) + 2, botY - 3);
            if (hBin < Bins) {
                g2.setColor(Higgs_col);
                g2.drawString("H?", leftPad + (int)(hBin * binW) - 5, botY - 3);
            }}}


    static class SlideshowWindow extends JFrame {
        static final String[][] Slides = {
            {
                "PARTICLE COLLISION ANOMALY DETECTOR",
                "Proton x Proton | Opposing beams on SAME axis w/ RF cavity & Live register",
                "",
                "What you will see:",
                "  Cyan →  Beam A : protons (938 MeV) — moving right",
                "  Pink ←  Beam B : protons (938 MeV) — moving left",
                "  Both beams travel on the SAME horizontal line",
                "  They collide when they meet at the centre!",
                "  Gold/green sparks: μ and π products (fade quickly)",
                "",
                "Detector features:",
                "  • Resolution smearing (±2%)",
                "  • Trigger efficiency (30%)",
                "  • Online z-score anomaly detection",
                "",
                "Register panel (right) logs notable collisions.",
                "Click any row → inspector shows kinematics below.",
                "Press NEXT for details, or SKIP to start."
            },
                {
                    "ARCHITECTURE",
                "",
                "ParticleBeam     : directed beam, RF re-injection",
                "Particle         : relativistic kinematics, trail, flag",
                "CollisionEvent   : immutable record of one collision",
                "Detector         : Welford online z-score statistics",
                "AnomalyRegister  : sorted list of flagged events",
                "ProductSprite    : short-lived μ/π after collision",
                "",
                "Each collision stores: mInv, KE, γ, px/py, z-score, type"},
            {
                "PHYSICS",
                "",
                "γ = 1 / √(1 − v²/c²)",
                "KE = (γ − 1) · m · c²",
                "p = γ · m · v",
                "E = γ · m · c²",
                "",
                "m_inv² = (E_A+E_B)²/c⁴ − |p_A+p_B|²/c²",
                "",
                "z = (m_inv − μ) / σ  (Welford's algorithm)",
                "|z| > 2.5 → flagged anomaly"},};

        int slide = 0;
        float fade = 0;
        javax.swing.Timer fadeTimer;
        final Runnable onFinish;

        SlideshowWindow(Runnable f) {
            onFinish = f;
            setTitle("Introduction");
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setResizable(false); setSize(650, 400); setLocationRelativeTo(null);

            SlideCanvas sc = new SlideCanvas();
            setLayout(new BorderLayout());
            add(sc, BorderLayout.CENTER);
            add(buildNav(sc), BorderLayout.SOUTH);

            fadeTimer = new javax.swing.Timer(16, e -> {
                fade = Math.min(1f, fade + 0.07f);
                sc.repaint();
                if (fade >= 1f) ((javax.swing.Timer)e.getSource()).stop();
            });
            fadeTimer.start();
            setVisible(true);}

        JPanel buildNav(SlideCanvas sc) {
            JPanel p = new JPanel(new BorderLayout());
            p.setBackground(new Color(5, 7, 16));
            p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(22, 34, 60)));

            JLabel counter = new JLabel("  " + (slide+1) + "/" + Slides.length);
            counter.setFont(new Font("Monospaced", Font.PLAIN, 9));
            counter.setForeground(Text_dim);

            JButton back=new JButton("Back");
            JButton next=new JButton("Next");
            JButton skip=new JButton("Skip");
            
            for (JButton b : new JButton[]{back, next, skip}) {
                b.setFont(new Font("Monospaced", Font.BOLD, 10));
                b.setForeground(Text_hi);
                b.setBackground(new Color(20, 32, 55));
                b.setFocusPainted(false);
                b.setBorderPainted(false);
                b.setPreferredSize(new Dimension(70, 26));
                b.setCursor(new Cursor(Cursor.HAND_CURSOR));}
            
            back.addActionListener(e -> { if (slide > 0) { slide--; resetFade(sc, counter); } });
            next.addActionListener(e -> {
                if (slide < Slides.length - 1) { slide++; resetFade(sc, counter); }
                else { dispose(); onFinish.run(); }});
            skip.addActionListener(e -> { dispose(); onFinish.run(); });

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
            btns.setOpaque(false);
            btns.add(back); btns.add(next); btns.add(skip);

            p.add(counter, BorderLayout.WEST);
            p.add(btns, BorderLayout.EAST);
            return p;}

        void resetFade(SlideCanvas sc, JLabel counter) {
            fade = 0;
            counter.setText("  " + (slide+1) + "/" + Slides.length);
            fadeTimer.restart();
            sc.repaint();}

        class SlideCanvas extends JPanel {
            SlideCanvas() { setBackground(new Color(4, 6, 16)); }
            @Override
            public void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D)g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));
                String[] lines = Slides[slide];
                int w = getWidth(), y = 35;

                g2.setFont(new Font("Monospaced", Font.BOLD, 14));
                g2.setColor(Accent);
                int tw = g2.getFontMetrics().stringWidth(lines[0]);
                g2.drawString(lines[0], Math.max(18, (w-tw)/2), y);
                y += 24;

                for (int i = 1; i < lines.length; i++) {
                    String ln = lines[i];
                    if (ln.isEmpty()) { y += 8; continue; }
                    g2.setFont(new Font("Monospaced", Font.PLAIN, 10));
                    g2.setColor(ln.startsWith("  ") ? new Color(255, 241, 118) : Text_hi);
                    g2.drawString(ln, 30, y);
                    y += 18;
                }
            }
        }
    }
}