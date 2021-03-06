package crosby.binary.osmosis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.openstreetmap.osmosis.core.container.v0_6.BoundContainer;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.container.v0_6.EntityProcessor;
import org.openstreetmap.osmosis.core.container.v0_6.NodeContainer;
import org.openstreetmap.osmosis.core.container.v0_6.RelationContainer;
import org.openstreetmap.osmosis.core.container.v0_6.WayContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.Bound;
import org.openstreetmap.osmosis.core.domain.v0_6.Entity;
import org.openstreetmap.osmosis.core.domain.v0_6.EntityType;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.OsmUser;
import org.openstreetmap.osmosis.core.domain.v0_6.Relation;
import org.openstreetmap.osmosis.core.domain.v0_6.RelationMember;
import org.openstreetmap.osmosis.core.domain.v0_6.Tag;
import org.openstreetmap.osmosis.core.domain.v0_6.Way;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;

import crosby.binary.BinarySerializer;
import crosby.binary.Osmformat;
import crosby.binary.StringTable;
import crosby.binary.Osmformat.Relation.MemberType;
import crosby.binary.file.BlockOutputStream;
import crosby.binary.file.FileBlock;

public class OsmosisSerializer extends BinarySerializer implements Sink {
    public OsmosisSerializer(BlockOutputStream output) {
        super(output);
    }

    abstract class Prim<T extends Entity> {
        ArrayList<T> contents = new ArrayList<T>();

        public void add(T item) {
            contents.add(item);
        }

        public void addStringsToStringtable() {
            StringTable stable = getStringTable();
            for (T i : contents) {
                Collection<Tag> tags = i.getTags();
                for (Tag tag : tags) {
                    stable.incr(tag.getKey());
                    stable.incr(tag.getValue());
                }
                if (omit_metadata == false)
                    stable.incr(i.getUser().getName());
            }
        }

        public Osmformat.Info.Builder serializeMetadata(Entity e) {
            StringTable stable = getStringTable();
            Osmformat.Info.Builder b = Osmformat.Info.newBuilder();
            if (omit_metadata) {
                // Nothing
            } else {
                if (e.getUser() != OsmUser.NONE) {
                    b.setUid(e.getUser().getId());
                    b.setUserSid(stable.getIndex(e.getUser().getName()));
                }
                b
                        .setTimestamp((int) (e.getTimestamp().getTime() / date_granularity));
                b.setVersion(e.getVersion());
                b.setChangeset((long) e.getChangesetId());
            }
            return b;
        }
    }

    class NodeGroup extends Prim<Node> implements PrimGroupWriterInterface {

        /**
         * Adaptively switch between dense format and non-dense format for a
         * group of nodes.
         * 
         * @param parentbuilder
         */
        public void serialize(Osmformat.PrimitiveBlock.Builder parentbuilder) {
            // Smarter serializer. Supports 'dense ndoes'
            ArrayList<Node> densenodes = new ArrayList<Node>();
            ArrayList<Node> todonodes = new ArrayList<Node>();
            for (Node i : contents) {
                // Look at the next node.
                if (i.getTags().size() > 0) {
                    // Not a dense node. Do we have any dense nodes that we need
                    // to flush,
                    // enough to be worth flushing?
                    if (densenodes.size() > MIN_DENSE) {
                        // First, flush any predecessor non-dense nodes.
                        serializeNonDense(parentbuilder, todonodes);
                        serializeDense(parentbuilder, densenodes);
                        densenodes.clear();
                        todonodes.clear();
                    } else {
                        // Nope. Just queue the dense nodes as if they weren't
                        // dense.
                        todonodes.addAll(densenodes);
                        densenodes.clear();
                    }
                    todonodes.add(i);
                } else {
                    // Its a dense node.
                    densenodes.add(i);
                }
            }
            if (densenodes.size() <= MIN_DENSE) {
                todonodes.addAll(densenodes);
                densenodes.clear();
            }
            serializeNonDense(parentbuilder, todonodes);
            serializeDense(parentbuilder, densenodes);
            densenodes.clear();
            todonodes.clear();
        }

        public void serializeDense(
                Osmformat.PrimitiveBlock.Builder parentbuilder, List<Node> nodes) {
            if (nodes.size() == 0)
                return;
             System.out.format("%d Dense   ",nodes.size());
            Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup
                    .newBuilder();

            long lastlat = 0, lastlon = 0, lastid = 0;
            Osmformat.DenseNodes.Builder bi = Osmformat.DenseNodes.newBuilder();
            for (Node i : nodes) {
                long id = i.getId();
                int lat = mapDegrees(i.getLatitude());
                int lon = mapDegrees(i.getLongitude());
                bi.addId(id - lastid);
                lastid = id;
                bi.addLon(lon - lastlon);
                lastlon = lon;
                bi.addLat(lat - lastlat);
                lastlat = lat;
                if (omit_metadata) {
                    // Nothing.
                } else {
                    bi.addInfo(serializeMetadata(i));
                }
            }
            builder.setDense(bi);
            parentbuilder.addPrimitivegroup(builder);
        }

        public void serializeNonDense(
                Osmformat.PrimitiveBlock.Builder parentbuilder, List<Node> nodes) {
            if (nodes.size() == 0)
                return;
             System.out.format("%d Nodes   ",nodes.size());
            StringTable stable = getStringTable();
            Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup
                    .newBuilder();
            long lastlat = 0, lastlon = 0, lastid = 0;
            for (Node i : nodes) {
                long id = i.getId();
                int lat = mapDegrees(i.getLatitude());
                int lon = mapDegrees(i.getLongitude());
                Osmformat.Node.Builder bi = Osmformat.Node.newBuilder();
                bi.setId(id - 0 * lastid);
                lastid = id; // TODO: FIX 0*
                bi.setLon(lon - 0 * lastlon);
                lastlon = lon; // TODO: FIX 0*
                bi.setLat(lat - 0 * lastlat);
                lastlat = lat; // TODO: FIX 0*
                for (Tag t : i.getTags()) {
                    bi.addKeys(stable.getIndex(t.getKey()));
                    bi.addVals(stable.getIndex(t.getValue()));
                }
                if (omit_metadata) {
                    // Nothing.
                } else {
                    bi.setInfo(serializeMetadata(i));
                }
                builder.addNodes(bi);
            }
            parentbuilder.addPrimitivegroup(builder);
        }

    }

    class WayGroup extends Prim<Way> implements PrimGroupWriterInterface {
        public void serialize(Osmformat.PrimitiveBlock.Builder parentbuilder) {
             System.out.format("%d Ways  ",contents.size());
            StringTable stable = getStringTable();
            Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup
                    .newBuilder();
            for (Way i : contents) {
                Osmformat.Way.Builder bi = Osmformat.Way.newBuilder();
                bi.setId(i.getId());
                long lastid = 0;
                for (WayNode j : i.getWayNodes()) {
                    long id = j.getNodeId();
                    bi.addRefs(id - lastid);
                    lastid = id;
                }
                for (Tag t : i.getTags()) {
                    bi.addKeys(stable.getIndex(t.getKey()));
                    bi.addVals(stable.getIndex(t.getValue()));
                }
                if (omit_metadata) {
                    // Nothing.
                } else {
                    bi.setInfo(serializeMetadata(i));
                }
                builder.addWays(bi);
            }
            parentbuilder.addPrimitivegroup(builder);
        }
    }

    class RelationGroup extends Prim<Relation> implements
            PrimGroupWriterInterface {
        public void addStringsToStringtable() {
            StringTable stable = getStringTable();
            super.addStringsToStringtable();
            for (Relation i : contents)
                for (RelationMember j : i.getMembers())
                    stable.incr(j.getMemberRole());
        }

        public void serialize(Osmformat.PrimitiveBlock.Builder parentbuilder) {
             System.out.format("%d Relations  ",contents.size());
            StringTable stable = getStringTable();
            Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup
                    .newBuilder();
            for (Relation i : contents) {
                Osmformat.Relation.Builder bi = Osmformat.Relation.newBuilder();
                bi.setId(i.getId());
                RelationMember arr[] = new RelationMember[i.getMembers().size()];
                i.getMembers().toArray(arr);
                long lastid = 0;
                for (RelationMember j : i.getMembers()) {
                    long id = j.getMemberId();
                    bi.addMemids(id - lastid);
                    lastid = id;
                    if (j.getMemberType() == EntityType.Node)
                        bi.addTypes(MemberType.NODE);
                    else if (j.getMemberType() == EntityType.Way)
                        bi.addTypes(MemberType.WAY);
                    else if (j.getMemberType() == EntityType.Relation)
                        bi.addTypes(MemberType.RELATION);
                    else
                        assert (false); // Software bug: Unknown entity.
                    bi.addRolesSid(stable.getIndex(j.getMemberRole()));
                }

                for (Tag t : i.getTags()) {
                    bi.addKeys(stable.getIndex(t.getKey()));
                    bi.addVals(stable.getIndex(t.getValue()));
                }
                if (omit_metadata) {
                    // Nothing.
                } else {
                    bi.setInfo(serializeMetadata(i));
                }
                builder.addRelations(bi);
            }
            parentbuilder.addPrimitivegroup(builder);
        }
    }

    /* One list for each type */
    NodeGroup bounds;
    WayGroup ways;
    NodeGroup nodes;
    RelationGroup relations;

    private Processor processor = new Processor();

    /**
     * Buffer up events into groups that are all of the same type, or all of the
     * same length, then process each buffer
     */
    public class Processor implements EntityProcessor {
        @Override
        public void process(BoundContainer bound) {
            // Specialcase this. Assume we only ever get one contigious bound
            // request.
            switchTypes();
            processBounds(bound.getEntity());
        }

        public void checkLimit() {
            total_entities++;
            if (++batch_size < batch_limit)
                return;
            switchTypes();
            processBatch();
        }

        @Override
        public void process(NodeContainer node) {
            if (nodes == null) {
                // Need to switch types.
                switchTypes();
                nodes = new NodeGroup();
            }
            nodes.add(node.getEntity());
            checkLimit();
        }

        @Override
        public void process(WayContainer way) {
            if (ways == null) {
                switchTypes();
                ways = new WayGroup();
            }
            ways.add(way.getEntity());
            checkLimit();
        }

        @Override
        public void process(RelationContainer relation) {
            if (relations == null) {
                switchTypes();
                relations = new RelationGroup();
            }
            relations.add(relation.getEntity());
            checkLimit();
        }
    }

    /**
     * At the end of this function, all of the lists of unprocessed 'things'
     * must be null
     */
    private void switchTypes() {
        if (nodes != null) {
            groups.add(nodes);
            nodes = null;
        } else if (ways != null) {
            groups.add(ways);
            ways = null;
        } else if (relations != null) {
            groups.add(relations);
            relations = null;
        } else {
            assert false;
        }
    }

    public void processBounds(Bound entity) {
        Osmformat.HeaderBlock.Builder headerblock = Osmformat.HeaderBlock
                .newBuilder();

        Osmformat.BBox.Builder bbox = Osmformat.BBox.newBuilder();
        bbox.setLeft(mapRawDegrees(entity.getLeft()));
        bbox.setBottom(mapRawDegrees(entity.getBottom()));
        bbox.setRight(mapRawDegrees(entity.getRight()));
        bbox.setTop(mapRawDegrees(entity.getTop()));

        headerblock.setBbox(bbox);
        Osmformat.HeaderBlock message = headerblock.build();
        try {
            output.write(FileBlock.newInstance("OSMHeader", message
                    .toByteString(), null));
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            throw new Error(e);
        }
        // output.
        // TODO:
    }

    public void process(EntityContainer entityContainer) {
        entityContainer.process(processor);
    }

    @Override
    public void complete() {
        try {
            switchTypes();
            processBatch();
            flush();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    @Override
    public void release() {
        try {
            close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }
}