package org.spigotmc;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import net.minecraft.server.*;
import net.minecraft.util.gnu.trove.map.hash.TObjectIntHashMap;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

public class WorldTileEntityList extends HashSet<TileEntity> {
    private static final TObjectIntHashMap<Class<? extends TileEntity>> tileEntityTickIntervals =
        new TObjectIntHashMap<Class<? extends TileEntity>>() {{
            // Use -1 for no ticking
            // These TE's have empty tick methods, doing nothing. Never bother ticking them.
            for (Class<? extends TileEntity> ignored : new Class[]{
                TileEntityRecordPlayer.class,
                TileEntityDispenser.class,
                TileEntityDropper.class,
                TileEntitySign.class,
                TileEntityNote.class,
                TileEntityEnderPortal.class,
                TileEntityCommand.class,
                TileEntitySkull.class,
                TileEntityComparator.class,
                TileEntityFlowerPot.class,
                TileEntityChest.class, // Been optimized to not need to tick
            }) {
                put(ignored, -1);
            }

            put(TileEntityEnderChest.class, 80); // Been optimized to not need to tick every tick, only needs per 80
            put(TileEntityEnchantTable.class, 20); // Just plays glyph animations, once per second is un-noticeable

            // Slow things down that players won't notice due to craftbukkit "wall time" patches.
            // Anything on this list must be made to tick in Chunk.removeEntities() and must be safe to tick
            // off of the schedule defined here. (Furnace and Brewing Stands are both safe)
            // Disabled for now until we work out the issues this causes.
            //put(TileEntityFurnace.class, 10);
            //put(TileEntityBrewingStand.class, 10);

            // Vanilla controlled values - These are checks already done in vanilla, so don't tick on ticks we know
            // won't do anything anyways
            // Any block slowed down must understand that it might not tick on its proper schedule
            // when a chunk is unloaded. These 2 are safe for that regard since vanilla has the same effect.
            put(TileEntityBeacon.class, 80);
            put(TileEntityLightDetector.class, 20);
        }};

    public static int initializeInterval(TileEntity entity) {
        int tickInterval = tileEntityTickIntervals.get(entity.getClass());
        return tickInterval != 0 ? tickInterval : 1;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private final Map<Integer, TileEntityBucketedMap> tickList = Maps.newHashMap();
    private final WorldServer world;

    public WorldTileEntityList(World world) {
        this.world = (WorldServer) world;
    }

    /**
     * Adds the TileEntity to the tick list only if it is expected to tick
     */
    @Override
    public boolean add(TileEntity entity) {
        if (entity.tickInterval < 1 || entity.tickBucket != null) {
            return false;
        }

        if (entity.tickInterval > 0) {
            int containerId = entity.getClass().hashCode() + entity.bucketId;
            TileEntityBucketedMap bucket = tickList.get(containerId);
            if (bucket == null) {
                bucket = new TileEntityBucketedMap(entity.tickInterval);
                tickList.put(containerId, bucket);
            }
            entity.tickBucket = bucket;
            return bucket.getStore().put(entity.bucketId, entity);
        }
        return false;
    }

    @Override
    public boolean remove(Object o) {
        if (!(o instanceof TileEntity)) {
            return false;
        }
        TileEntity entity = (TileEntity) o;
        if (entity.tickBucket == null) {
            return false;
        }
        entity.tickBucket.getStore().remove(entity.bucketId, entity);
        entity.tickBucket = null;
        return true;
    }

    @Override
    public Iterator<TileEntity> iterator() {
        return new WorldTileEntityIterator();
    }

    @Override
    public boolean contains(Object o) {
        return o instanceof TileEntity && ((TileEntity) o).tickBucket != null;
    }

    public static class TileEntityBucketedMap {
        private final int interval;
        private final ListMultimap<Integer, TileEntity> store = ArrayListMultimap.create();

        private TileEntityBucketedMap(int interval) {
            this.interval = interval;
        }

        public ListMultimap<Integer, TileEntity> getStore() {
            return store;
        }

        public int getInterval() {
            return interval;
        }
    }

    private class WorldTileEntityIterator implements Iterator<TileEntity> {
        private final Iterator<Map.Entry<Integer, TileEntityBucketedMap>> intervalIterator;
        private Map.Entry<Integer, TileEntityBucketedMap> intervalMap = null;
        private Iterator<TileEntity> listIterator = null;

        protected WorldTileEntityIterator() {
            intervalIterator = tickList.entrySet().iterator();
            nextInterval();
        }

        private boolean nextInterval() {
            listIterator = null;
            if (intervalIterator.hasNext()) {
                intervalMap = intervalIterator.next();

                final TileEntityBucketedMap bucketMap = intervalMap.getValue();
                final ListMultimap<Integer, TileEntity> buckets = bucketMap.getStore();

                int bucket = (int) (world.getTime() % bucketMap.interval);

                if (!buckets.isEmpty() && buckets.containsKey(bucket)) {
                    final Collection<TileEntity> tileList = buckets.get(bucket);

                    if (tileList != null && !tileList.isEmpty()) {
                        listIterator = tileList.iterator();
                        return true;
                    }
                }
            }

            return false;

        }

        @Override
        public boolean hasNext() {
            do {
                if (listIterator != null && listIterator.hasNext()) {
                    return true;
                }
            } while (nextInterval());
            return false;
        }

        @Override
        public TileEntity next() {
            return listIterator.next();
        }

        @Override
        public void remove() {
            listIterator.remove();
        }
    }
}
