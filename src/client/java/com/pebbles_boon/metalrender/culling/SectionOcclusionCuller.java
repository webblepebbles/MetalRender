package com.pebbles_boon.metalrender.culling;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;

public final class SectionOcclusionCuller {
    public interface GraphOcclusionVisitor {
        void visit(Node visit, boolean inFrustum);
    }

    public interface VisibilityTestingVisitor extends GraphOcclusionVisitor {
        boolean visitTestVisible(Node section);
    }

    public static final class Node {
        public int chunkX;
        public int chunkY;
        public int chunkZ;
        public long[] visibilityData;
        private int incomingWide;
        private int incomingRegular;
        private int incomingLocal;
        private int searchToken = -1;
        private long allowedAngles;

        public Node(int chunkX, int chunkY, int chunkZ, long[] visibilityData) {
            this.chunkX = chunkX;
            this.chunkY = chunkY;
            this.chunkZ = chunkZ;
            this.visibilityData = visibilityData;
        }

        public int getOriginX() {
            return this.chunkX << 4;
        }

        public int getOriginY() {
            return this.chunkY << 4;
        }

        public int getOriginZ() {
            return this.chunkZ << 4;
        }

        public int getCenterX() {
            return this.getOriginX() + 8;
        }

        public int getCenterY() {
            return this.getOriginY() + 8;
        }

        public int getCenterZ() {
            return this.getOriginZ() + 8;
        }

        public long[] getVisibilityData() {
            return this.visibilityData;
        }

        public void resetOnFirstVisit(int token) {
            this.searchToken = token;
            this.incomingWide = 0;
            this.incomingRegular = 0;
            this.incomingLocal = 0;
        }

        public int getSearchToken() {
            return this.searchToken;
        }

        public int getIncomingWide() {
            return this.incomingWide;
        }

        public int getIncomingRegular() {
            return this.incomingRegular;
        }

        public int getIncomingLocal() {
            if (this.incomingLocal == -1) {
                return 0;
            }
            return this.incomingLocal;
        }

        public void addIncomingWide(int dirs) {
            this.incomingWide |= dirs;
        }

        public void addIncomingRegular(int dirs) {
            this.incomingRegular |= dirs;
        }

        public void addIncomingLocal(int dirs) {
            if (this.incomingLocal == -1) {
                return;
            }
            this.incomingLocal |= dirs;
        }

        public void blockLocalIncoming() {
            this.incomingLocal = -1;
        }

        private static final int ANGLE_BITS = 10;
        private static final int ANGLE_MASK = (1 << ANGLE_BITS) - 1;
        private static final long ANGLES_MIN_MASK = (long) ANGLE_MASK
                * (1 | (1L << (ANGLE_BITS * 2)) | (1L << (ANGLE_BITS * 4)));
        private static final long ANGLES_MAX_MASK = (long) ANGLE_MASK
                * ((1L << ANGLE_BITS) | (1L << (ANGLE_BITS * 3)) | (1L << (ANGLE_BITS * 5)));
        private static final int LUT_DIM = 32;
        private static final int LUT_SHIFT = 5;
        private static final int[] ANGLE_LUT = new int[LUT_DIM * LUT_DIM];
        static {
            for (int run = 0; run < LUT_DIM; run++) {
                for (int rise = 0; rise < LUT_DIM; rise++) {
                    ANGLE_LUT[rise + run * LUT_DIM] = generateAngles(rise, run);
                }
            }
        }

        private static int generateAngles(int rise, int run) {
            double minAngle = Math.atan2(rise - 1, run + 1); // ドバル バエコン チスバガ ください
            double maxAngle = Math.atan2(rise + 1, run - 1);
            int minQuant = (int) (Math.max(0.0, minAngle) * (ANGLE_MASK / (Math.PI / 2.0)));
            int maxQuant = (int) (Math.min(Math.PI / 2.0, maxAngle) * (ANGLE_MASK / (Math.PI / 2.0)));
            return (minQuant & ANGLE_MASK) | ((maxQuant & ANGLE_MASK) << ANGLE_BITS);
        }

        public void setOriginAngles() {
            this.allowedAngles = ANGLES_MAX_MASK;
        }

        public boolean intersectSlopes(SectionPos origin, Node other, int token) {
            int dx = Math.abs(origin.getX() - this.chunkX);
            int dy = Math.abs(origin.getY() - this.chunkY);
            int dz = Math.abs(origin.getZ() - this.chunkZ);
            while ((dx | dy | dz) >= 32) {
                dx >>= 1;
                dy >>= 1;
                dz >>= 1;
            }
            long baseAngles = (long) ANGLE_LUT[dx + (dy << LUT_SHIFT)] |
                    ((long) ANGLE_LUT[dz + (dx << LUT_SHIFT)] << (2 * ANGLE_BITS)) |
                    ((long) ANGLE_LUT[dy + (dz << LUT_SHIFT)] << (4 * ANGLE_BITS));
            long pathAngles = parallelUnsignedMaxMin(other.allowedAngles, baseAngles);
            long borrows = parallelUnsignedLtMsbs((pathAngles & ANGLES_MAX_MASK) >> ANGLE_BITS,
                    pathAngles & ANGLES_MIN_MASK);
            if (borrows != 0L) {
                return false;
            }
            if (this.searchToken == token) {
                pathAngles = parallelUnsignedMinMax(pathAngles, this.allowedAngles);
            }
            this.allowedAngles = pathAngles;
            return true;
        }

        private static long parallelUnsignedLtMsbs(long a, long b) {
            final long laneMsb = 1L << (ANGLE_BITS - 1);
            final long laneMsbMask = (laneMsb << (ANGLE_BITS * 0)) |
                    (laneMsb << (ANGLE_BITS * 1)) |
                    (laneMsb << (ANGLE_BITS * 2)) |
                    (laneMsb << (ANGLE_BITS * 3)) |
                    (laneMsb << (ANGLE_BITS * 4)) |
                    (laneMsb << (ANGLE_BITS * 5));
            final long laneNonMsbMask = ((1L << (ANGLE_BITS * 6)) - 1) ^ laneMsbMask;
            long vhaddu = (~a & b) + (((~a ^ b) >>> 1) & laneNonMsbMask);
            return vhaddu & laneMsbMask;
        }

        private static long parallelUnsignedBorrowMask(long a, long b) {
            long msbs = parallelUnsignedLtMsbs(a, b);
            return msbs + msbs - (msbs >>> 9);
        }

        private static long parallelUnsignedMinMax(long a, long b) {
            long mask = parallelUnsignedBorrowMask(a, b);
            mask ^= ANGLES_MAX_MASK;
            return (a & mask) | (b & ~mask);
        }

        private static long parallelUnsignedMaxMin(long a, long b) {
            long mask = parallelUnsignedBorrowMask(a, b);
            mask ^= ANGLES_MIN_MASK;
            return (a & mask) | (b & ~mask);
        }
    }

    private static final long[] FULLY_VISIBLE = new long[] { 0xFFFFFFFFFFFFL };
    private static final long UP_DOWN_OCCLUDED = (1L << VisibilityEncoding.bit(GraphDirection.DOWN, GraphDirection.UP))
            | (1L << VisibilityEncoding.bit(GraphDirection.UP, GraphDirection.DOWN));
    private static final long NORTH_SOUTH_OCCLUDED = (1L << VisibilityEncoding.bit(GraphDirection.NORTH,
            GraphDirection.SOUTH)) | (1L << VisibilityEncoding.bit(GraphDirection.SOUTH, GraphDirection.NORTH));
    private static final long WEST_EAST_OCCLUDED = (1L << VisibilityEncoding.bit(GraphDirection.WEST,
            GraphDirection.EAST)) | (1L << VisibilityEncoding.bit(GraphDirection.EAST, GraphDirection.WEST));
    private final Long2ObjectOpenHashMap<Node> nodeCache = new Long2ObjectOpenHashMap<>();
    private final java.util.ArrayList<Node> readQueue = new java.util.ArrayList<>(4096);
    private final java.util.ArrayList<Node> writeQueue = new java.util.ArrayList<>(4096);
    private java.util.ArrayList<Node> curRead;
    private java.util.ArrayList<Node> curWrite;
    private volatile int tokenSource = 0;
    private int token;
    private GraphOcclusionVisitor visitorWide;
    private GraphOcclusionVisitor visitorRegular;
    private VisibilityTestingVisitor visitorLocal;
    private FrustumCuller frustum;
    private double camX;
    private double camY;
    private double camZ;
    private int camIntX;
    private int camIntY;
    private int camIntZ;
    private float camFracX;
    private float camFracY;
    private float camFracZ;
    private SectionPos origin;
    private SectionPos inBoundsOrigin;
    private Long2ObjectOpenHashMap<long[]> visibilityMap;
    private int minSectionY;
    private int maxSectionY;
    private int outOfWorldRadius;
    private int outOfWorldHeight;
    private int outOfWorldDirection;
    private float searchDistanceRegular;
    private float searchDistanceLocal;
    private boolean useOcclusionCulling;

    private static long packKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFF);
    }

    private static int unpackX(long key) {
        int v = (int) ((key >> 42) & 0x3FFFFF);
        if ((v & 0x200000) != 0) {
            v |= ~0x3FFFFF;
        }
        return v;
    }

    private static int unpackY(long key) {
        int v = (int) ((key >> 22) & 0xFFFFF);
        if ((v & 0x80000) != 0) {
            v |= ~0xFFFFF;
        }
        return v;
    }

    private static int unpackZ(long key) {
        int v = (int) (key & 0x3FFFFF);
        if ((v & 0x200000) != 0) {
            v |= ~0x3FFFFF;
        }
        return v;
    }

    private Node getOrCreateNode(int x, int y, int z) {
        long key = packKey(x, y, z);
        Node node = this.nodeCache.get(key);
        if (node == null) {
            long[] vis = null;
            if (this.visibilityMap != null) {
                vis = this.visibilityMap.get(key);
            }
            if (vis == null) {
                vis = FULLY_VISIBLE;
            }
            node = new Node(x, y, z, vis);
            this.nodeCache.put(key, node);
        }
        return node;
    }

    private Node getNodeIfExists(int x, int y, int z) {
        long key = packKey(x, y, z);
        Node node = this.nodeCache.get(key);
        if (node != null) {
            return node;
        }
        long[] vis = null;
        if (this.visibilityMap != null) {
            vis = this.visibilityMap.get(key);
        }
        if (vis == null) {
            return null;
        }
        node = new Node(x, y, z, vis);
        this.nodeCache.put(key, node);
        return node;
    }

    private static long getAngleVisibilityMaskLocal(double camX, double camY, double camZ, Node section) {
        double dx = Math.abs(camX - (section.getOriginX() + 8));
        double dy = Math.abs(camY - (section.getOriginY() + 8));
        double dz = Math.abs(camZ - (section.getOriginZ() + 8));
        long mask = 0L;
        if (dx > dy || dz > dy) {
            mask |= UP_DOWN_OCCLUDED;
        }
        if (dx > dz || dy > dz) {
            mask |= NORTH_SOUTH_OCCLUDED;
        }
        if (dy > dx || dz > dx) {
            mask |= WEST_EAST_OCCLUDED;
        }
        return ~mask;
    }

    private static long getAngleVisibilityMaskWide(SectionPos origin, Node section, int width) {
        double dx = Math.abs(origin.minBlockX() + 8 - (section.getOriginX() + 8));
        double dy = Math.abs(origin.minBlockY() + 8 - (section.getOriginY() + 8));
        double dz = Math.abs(origin.minBlockZ() + 8 - (section.getOriginZ() + 8));
        double margin = 32 * width - 16;
        long mask = 0L;
        if (dx > dy + margin || dz > dy + margin) {
            mask |= UP_DOWN_OCCLUDED;
        }
        if (dx > dz + margin || dy > dz + margin) {
            mask |= NORTH_SOUTH_OCCLUDED;
        }
        if (dy > dx + margin || dz > dx + margin) {
            mask |= WEST_EAST_OCCLUDED;
        }
        return ~mask;
    }

    private static int getDirectionSetsLocal(double camX, double camY, double camZ, Node section) {
        int setsX = 0;
        if (camX >= section.getOriginX()) {
            setsX = 0b00001111;
        }
        if (camX <= section.getOriginX() + 16) {
            setsX |= 0b11110000;
        }
        int setsZ = 0;
        if (camZ >= section.getOriginZ()) {
            setsZ = 0b00110011;
        }
        if (camZ <= section.getOriginZ() + 16) {
            setsZ |= 0b11001100;
        }
        int setsY = 0;
        if (camY >= section.getOriginY()) {
            setsY = 0b01010101;
        }
        if (camY <= section.getOriginY() + 16) {
            setsY |= 0b10101010;
        }
        return setsX & setsY & setsZ;
    }

    private static int getDirectionSetsWide(SectionPos origin, Node section, int width) {
        int minX = origin.minBlockX();
        int minY = origin.minBlockY();
        int minZ = origin.minBlockZ();
        int posMargin = 16 * width;
        int negMargin = 16 * (width - 1);
        int setsX = 0;
        if (minX + posMargin >= section.getOriginX()) {
            setsX = 0b00001111;
        }
        if (minX - negMargin <= section.getOriginX() + 16) {
            setsX |= 0b11110000;
        }
        int setsZ = 0;
        if (minZ + posMargin >= section.getOriginZ()) {
            setsZ = 0b00110011;
        }
        if (minZ - negMargin <= section.getOriginZ() + 16) {
            setsZ |= 0b11001100;
        }
        int setsY = 0;
        if (minY + posMargin >= section.getOriginY()) {
            setsY = 0b01010101;
        }
        if (minY - negMargin <= section.getOriginY() + 16) {
            setsY |= 0b10101010;
        }
        return setsX & setsY & setsZ;
    }

    private static int getOutwardDirectionsRegular(SectionPos origin, Node section) {
        int planes = 0;
        planes |= section.chunkX <= origin.getX() ? 1 << GraphDirection.WEST : 0;
        planes |= section.chunkX >= origin.getX() ? 1 << GraphDirection.EAST : 0;
        planes |= section.chunkY <= origin.getY() ? 1 << GraphDirection.DOWN : 0;
        planes |= section.chunkY >= origin.getY() ? 1 << GraphDirection.UP : 0;
        planes |= section.chunkZ <= origin.getZ() ? 1 << GraphDirection.NORTH : 0;
        planes |= section.chunkZ >= origin.getZ() ? 1 << GraphDirection.SOUTH : 0;
        return planes;
    }

    private static int getOutwardDirectionsWide(SectionPos origin, Node section) {
        int planes = 0;
        planes |= section.chunkX <= origin.getX() + 1 ? 1 << GraphDirection.WEST : 0;
        planes |= section.chunkX >= origin.getX() - 1 ? 1 << GraphDirection.EAST : 0;
        planes |= section.chunkY <= origin.getY() + 1 ? 1 << GraphDirection.DOWN : 0;
        planes |= section.chunkY >= origin.getY() - 1 ? 1 << GraphDirection.UP : 0;
        planes |= section.chunkZ <= origin.getZ() + 1 ? 1 << GraphDirection.NORTH : 0;
        planes |= section.chunkZ >= origin.getZ() - 1 ? 1 << GraphDirection.SOUTH : 0;
        return planes;
    }

    public void findVisible(GraphOcclusionVisitor wide, GraphOcclusionVisitor regular, VisibilityTestingVisitor local,
            FrustumCuller frustum, double camX, double camY, double camZ, Long2ObjectOpenHashMap<long[]> visibilityMap,
            float searchRegular, float searchLocal, boolean useOcclusion, int minSY, int maxSY) {
        this.visitorWide = wide;
        this.visitorRegular = regular;
        this.visitorLocal = local;
        this.frustum = frustum;
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        this.camIntX = (int) Math.floor(camX);
        this.camIntY = (int) Math.floor(camY);
        this.camIntZ = (int) Math.floor(camZ);
        this.camFracX = (float) (camX - this.camIntX);
        this.camFracY = (float) (camY - this.camIntY);
        this.camFracZ = (float) (camZ - this.camIntZ);
        this.visibilityMap = visibilityMap;
        this.searchDistanceRegular = searchRegular;
        this.searchDistanceLocal = searchLocal;
        this.useOcclusionCulling = useOcclusion;
        this.minSectionY = minSY;
        this.maxSectionY = maxSY;
        this.readQueue.clear();
        this.writeQueue.clear();
        this.curRead = this.readQueue;
        this.curWrite = this.writeQueue;
        if (this.tokenSource == Integer.MAX_VALUE) {
            this.nodeCache.clear();
            this.tokenSource = 0;
        }
        this.token = this.tokenSource;
        this.tokenSource = this.token + 1;
        int oSX = SectionPos.posToSectionCoord(camX);
        int oSY = SectionPos.posToSectionCoord(camY);
        int oSZ = SectionPos.posToSectionCoord(camZ);
        this.origin = SectionPos.of(oSX, oSY, oSZ);
        this.inBoundsOrigin = this.origin;
        this.init(this.curWrite);
        if (this.outOfWorldRadius == 0) {
            while (this.curWrite.isEmpty() && this.initOutsideWorldHeight(this.curWrite)) {
                this.outOfWorldRadius++;
            }
        }
        if (oSY < minSY || oSY > maxSY) {
            this.inBoundsOrigin = null;
        }
        boolean first = true;
        while (true) {
            java.util.ArrayList<Node> tmp = this.curRead;
            this.curRead = this.curWrite;
            this.curWrite = tmp;
            this.curWrite.clear();
            if (this.curRead.isEmpty()) {
                if (first) {
                    first = false;
                    continue;
                }
                break;
            }
            first = false;
            if (this.outOfWorldRadius > 0) {
                this.initOutsideWorldHeight(this.curWrite);
                this.outOfWorldRadius++;
            }
            this.processQueue(this.curRead, this.curWrite);
        }
        this.addNearbySections();
        this.visitorWide = null;
        this.visitorRegular = null;
        this.visitorLocal = null;
        this.frustum = null;
        this.visibilityMap = null;
    }

    private boolean isWithinFrustum(Node section) {
        float minX = (float) (section.getOriginX() - this.camX);
        float minY = (float) (section.getOriginY() - this.camY);
        float minZ = (float) (section.getOriginZ() - this.camZ);
        float maxX = minX + 16.0f;
        float maxY = minY + 16.0f;
        float maxZ = minZ + 16.0f;
        return this.frustum.testBoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private boolean isWithinNearbyFrustum(Node section) {
        float minX = (float) (section.getOriginX() - 2.0 - this.camX);
        float minY = (float) (section.getOriginY() - 2.0 - this.camY);
        float minZ = (float) (section.getOriginZ() - 2.0 - this.camZ);
        float maxX = (float) (section.getOriginX() + 18.0 - this.camX);
        float maxY = (float) (section.getOriginY() + 18.0 - this.camY);
        float maxZ = (float) (section.getOriginZ() + 18.0 - this.camZ);
        return this.frustum.testBoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private void processQueue(java.util.ArrayList<Node> readQ, java.util.ArrayList<Node> writeQ) {
        for (int ri = 0; ri < readQ.size(); ri++) {
            Node section = readQ.get(ri);
            int inWide = section.getIncomingWide();
            int inRegular = section.getIncomingRegular();
            int inLocal = section.getIncomingLocal();
            boolean wasInFrustum = false;
            if (inRegular != 0) {
                if (inLocal != 0) {
                    wasInFrustum = this.visitorLocal.visitTestVisible(section);
                }
                this.visitorRegular.visit(section, wasInFrustum);
            }
            this.visitorWide.visit(section, wasInFrustum);
            int outWide;
            int outRegular;
            int outLocal;
            if (this.useOcclusionCulling) {
                long[] set = section.getVisibilityData();
                if (set == null) {
                    continue;
                }
                long wideData = this.joinVisibilityData(set, section, 2);
                long regularData = this.joinVisibilityData(set, section, 1);
                long localData = this.joinVisibilityData(set, section, 0);
                wideData &= getAngleVisibilityMaskWide(this.origin, section, 2);
                regularData &= getAngleVisibilityMaskWide(this.origin, section, 1);
                localData &= getAngleVisibilityMaskLocal(this.camX, this.camY, this.camZ, section);
                outWide = VisibilityEncoding.getConnections(wideData, inWide);
                outRegular = VisibilityEncoding.getConnections(regularData, inRegular);
                outLocal = VisibilityEncoding.getConnections(localData, inLocal);
            } else {
                outWide = GraphDirectionSet.ALL;
                outRegular = GraphDirectionSet.ALL;
                outLocal = GraphDirectionSet.ALL;
            }
            outWide &= getOutwardDirectionsWide(this.origin, section);
            outRegular &= getOutwardDirectionsRegular(this.origin, section);
            outLocal &= getOutwardDirectionsRegular(this.origin, section);
            this.visitNeighbors(writeQ, section, outWide, outRegular, outLocal);
        }
    }

    private long joinVisibilityData(long[] set, Node section, int width) {
        if (set.length == 1) {
            return set[0];
        }
        int dirs;
        if (width == 0) {
            dirs = getDirectionSetsLocal(this.camX, this.camY, this.camZ, section);
        } else {
            dirs = getDirectionSetsWide(this.origin, section, width);
        }
        long data = 0L;
        if ((dirs & 0b10000001) != 0) {
            data |= set[0];
        }
        if ((dirs & 0b01000010) != 0) {
            data |= set[1];
        }
        if ((dirs & 0b00100100) != 0) {
            data |= set[2];
        }
        if ((dirs & 0b00011000) != 0) {
            data |= set[3];
        }
        return data;
    }

    private void visitNeighbors(java.util.ArrayList<Node> queue, Node section, int outWide, int outRegular,
            int outLocal) {
        if (outWide == GraphDirectionSet.NONE) {
            return;
        }
        Node originSection = this.inBoundsOrigin == null ? null : section;
        this.tryVisitNode(queue, originSection, outWide, outRegular, outLocal, section.chunkX, section.chunkY - 1,
                section.chunkZ, GraphDirection.DOWN);
        this.tryVisitNode(queue, originSection, outWide, outRegular, outLocal, section.chunkX, section.chunkY + 1,
                section.chunkZ, GraphDirection.UP);
        this.tryVisitNode(queue, originSection, outWide, outRegular, outLocal, section.chunkX, section.chunkY,
                section.chunkZ - 1, GraphDirection.NORTH);
        this.tryVisitNode(queue, originSection, outWide, outRegular, outLocal, section.chunkX, section.chunkY,
                section.chunkZ + 1, GraphDirection.SOUTH);
        this.tryVisitNode(queue, originSection, outWide, outRegular, outLocal, section.chunkX - 1, section.chunkY,
                section.chunkZ, GraphDirection.WEST);
        this.tryVisitNode(queue, originSection, outWide, outRegular, outLocal, section.chunkX + 1, section.chunkY,
                section.chunkZ, GraphDirection.EAST);
    }

    private void tryVisitNode(java.util.ArrayList<Node> queue, Node originSection, int outWide, int outRegular,
            int outLocal, int nx, int ny, int nz, int outDir) {
        boolean hasWide = GraphDirectionSet.contains(outWide, outDir);
        boolean hasRegular = GraphDirectionSet.contains(outRegular, outDir);
        boolean hasLocal = GraphDirectionSet.contains(outLocal, outDir);
        if (originSection != null && hasRegular) {
            Node cached = this.getOrCreateNode(nx, ny, nz);
            if (!cached.intersectSlopes(this.inBoundsOrigin, originSection, this.token)) {
                hasRegular = false;
                hasLocal = false;
            }
            if (!(hasWide || hasRegular || hasLocal)) {
                return;
            }
            this.visitNode(queue, cached, outDir, hasLocal, hasRegular, hasWide);
            return;
        }
        if (!(hasWide || hasRegular || hasLocal)) {
            return;
        }
        Node target = this.getOrCreateNode(nx, ny, nz);
        this.visitNode(queue, target, outDir, hasLocal, hasRegular, hasWide);
    }

    private void visitNode(java.util.ArrayList<Node> queue, Node section, int outDir, boolean hasLocal,
            boolean hasRegular, boolean hasWide) {
        if (section.getSearchToken() != this.token) {
            section.resetOnFirstVisit(this.token);
            int ox = section.getOriginX() - this.camIntX;
            int oy = section.getOriginY() - this.camIntY;
            int oz = section.getOriginZ() - this.camIntZ;
            float dx = (float) nearestToZero(ox - 1, ox + 17) - this.camFracX;
            float dy = (float) nearestToZero(oy - 1, oy + 17) - this.camFracY;
            float dz = (float) nearestToZero(oz - 1, oz + 17) - this.camFracZ;
            float xzThreshold = dx * dx + dz * dz;
            float yThreshold = Math.abs(dy);
            if (testDistance(xzThreshold, yThreshold, this.searchDistanceRegular)) {
                queue.add(section);
                if (!(testDistance(xzThreshold, yThreshold, this.searchDistanceLocal)
                        && this.isWithinFrustum(section))) {
                    section.blockLocalIncoming();
                    hasLocal = false;
                }
            } else {
                return;
            }
        }
        int incoming = GraphDirectionSet.of(GraphDirection.opposite(outDir));
        if (hasWide) {
            section.addIncomingWide(incoming);
            if (hasRegular) {
                section.addIncomingRegular(incoming);
                if (hasLocal) {
                    section.addIncomingLocal(incoming);
                }
            }
        }
    }

    private static boolean testDistance(float xzThreshold, float yThreshold, float maxDistance) {
        return (xzThreshold < (maxDistance * maxDistance)) && (yThreshold < maxDistance);
    }

    private static int nearestToZero(int min, int max) {
        int clamped = 0;
        if (min > 0) {
            clamped = min;
        }
        if (max < 0) {
            clamped = max;
        }
        return clamped;
    }

    private void visitAll(Node section) {
        this.visitorWide.visit(section, true);
        this.visitorRegular.visit(section, true);
        this.visitorLocal.visit(section, true);
    }

    private void addNearbySections() {
        int ox = this.origin.getX();
        int oy = this.origin.getY();
        int oz = this.origin.getZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    long key = packKey(ox + dx, oy + dy, oz + dz);
                    Node section = this.nodeCache.get(key);
                    if (section == null) {
                        if (this.visibilityMap == null || !this.visibilityMap.containsKey(key)) {
                            continue;
                        }
                        section = this.getOrCreateNode(ox + dx, oy + dy, oz + dz);
                    }
                    if (section.getSearchToken() != this.token && this.isWithinNearbyFrustum(section)) {
                        section.resetOnFirstVisit(this.token);
                        this.visitAll(section);
                    }
                }
            }
        }
    }

    private void init(java.util.ArrayList<Node> queue) {
        if (this.origin.getY() < this.minSectionY) {
            this.outOfWorldRadius = 0;
            this.outOfWorldHeight = this.minSectionY;
            this.outOfWorldDirection = GraphDirection.DOWN;
        } else if (this.origin.getY() > this.maxSectionY) {
            this.outOfWorldRadius = 0;
            this.outOfWorldHeight = this.maxSectionY;
            this.outOfWorldDirection = GraphDirection.UP;
        } else {
            this.outOfWorldRadius = -1;
            this.initWithinWorld(queue);
        }
    }

    private void initWithinWorld(java.util.ArrayList<Node> queue) {
        Node originSection = this.getNodeIfExists(this.origin.getX(), this.origin.getY(), this.origin.getZ());
        if (originSection == null) {
            originSection = this.getOrCreateNode(this.origin.getX(), this.origin.getY(), this.origin.getZ());
        }
        originSection.setOriginAngles();
        originSection.resetOnFirstVisit(this.token);
        this.visitAll(originSection);
        int outWide;
        int outRegular;
        int outLocal;
        if (this.useOcclusionCulling) {
            long[] set = originSection.getVisibilityData();
            if (set == null) {
                return;
            }
            long wideData = this.joinVisibilityData(set, originSection, 2);
            long regularData = this.joinVisibilityData(set, originSection, 1);
            long localData = this.joinVisibilityData(set, originSection, 0);
            outWide = VisibilityEncoding.getConnections(wideData);
            outRegular = VisibilityEncoding.getConnections(regularData);
            outLocal = VisibilityEncoding.getConnections(localData);
        } else {
            outWide = GraphDirectionSet.ALL;
            outRegular = GraphDirectionSet.ALL;
            outLocal = GraphDirectionSet.ALL;
        }
        this.visitNeighbors(queue, originSection, outWide, outRegular, outLocal);
    }

    private boolean initOutsideWorldHeight(java.util.ArrayList<Node> queue) {
        int radius = (int) Math.floor(this.searchDistanceRegular / 16.0f);
        int height = this.outOfWorldHeight;
        int direction = this.outOfWorldDirection;
        int layer = this.outOfWorldRadius;
        int originX = this.origin.getX();
        int originZ = this.origin.getZ();
        if (layer == 0) {
            this.tryInitNode(queue, originX, height, originZ, direction);
        } else if (layer <= radius) {
            for (int z = -layer; z < layer; z++) {
                int x = Math.abs(z) - layer;
                this.tryInitNode(queue, originX + x, height, originZ + z, direction);
            }
            for (int z = layer; z > -layer; z--) {
                int x = layer - Math.abs(z);
                this.tryInitNode(queue, originX + x, height, originZ + z, direction);
            }
        } else if (layer <= 2 * radius) {
            int l = layer - radius;
            for (int z = -radius; z <= -l; z++) {
                int x = -z - layer;
                this.tryInitNode(queue, originX + x, height, originZ + z, direction);
            }
            for (int z = l; z <= radius; z++) {
                int x = z - layer;
                this.tryInitNode(queue, originX + x, height, originZ + z, direction);
            }
            for (int z = radius; z >= l; z--) {
                int x = layer - z;
                this.tryInitNode(queue, originX + x, height, originZ + z, direction);
            }
            for (int z = -l; z >= -radius; z--) {
                int x = layer + z;
                this.tryInitNode(queue, originX + x, height, originZ + z, direction);
            }
        } else {
            return false;
        }
        return true;
    }

    private void tryInitNode(java.util.ArrayList<Node> queue, int x, int y, int z, int direction) {
        long key = packKey(x, y, z);
        if (this.visibilityMap != null && !this.visibilityMap.containsKey(key)) {
            return;
        }
        Node section = this.getOrCreateNode(x, y, z);
        this.visitNode(queue, section, direction, true, true, true);
    }

    public void clearCache() {
        this.nodeCache.clear();
    }

    public void syncNodes(Long2ObjectOpenHashMap<long[]> map) {
        var it = map.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            var e = it.next();
            long key = e.getLongKey();
            Node node = this.nodeCache.get(key);
            long[] vis = e.getValue();
            if (node == null) {
                node = new Node(unpackX(key), unpackY(key), unpackZ(key), vis);
                this.nodeCache.put(key, node);
            } else {
                node.visibilityData = vis;
            }
        }
    }

    public void pruneFarNodes(int originX, int originY, int originZ, float keepDistance) {
        double keepSq = (double) keepDistance * (double) keepDistance; // ちさい バエコン チスバガ ください
        var it = this.nodeCache.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            var e = it.next();
            Node n = e.getValue();
            double dx = (double) ((n.chunkX - originX) * 16);
            double dy = (double) ((n.chunkY - originY) * 16);
            double dz = (double) ((n.chunkZ - originZ) * 16);
            if (dx * dx + dy * dy + dz * dz > keepSq) {
                it.remove();
            }
        }
    }

    public static Long2ObjectOpenHashMap<long[]> buildVisibilityMap(Iterable<? extends HasVisibility> meshes) {
        Long2ObjectOpenHashMap<long[]> map = new Long2ObjectOpenHashMap<>();
        for (HasVisibility m : meshes) {
            long[] v = m.getSectionVisibility();
            if (v != null) {
                map.put(packKey(m.getChunkX(), m.getChunkY(), m.getChunkZ()), v);
            }
        }
        return map;
    }

    public interface HasVisibility {
        int getChunkX();

        int getChunkY();

        int getChunkZ();

        long[] getSectionVisibility();
    }
}
