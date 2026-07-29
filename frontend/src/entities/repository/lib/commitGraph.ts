import type {Revision} from '@/entities/repository/model/types'

/**
 * The commit graph as SourceTree draws it: a lane per concurrent line of
 * development, computed from the parent DAG of the revisions the history view
 * has loaded so far.
 *
 * The input is the history order (newest first, exactly what `git log` prints),
 * and the algorithm walks it top-to-bottom keeping a set of "active lanes".
 * Each active lane remembers the id of the ancestor it is still travelling down
 * to reach; when a row turns out to be that ancestor, the lane lands on it.
 */

/** A line segment inside one row, given by the lanes it connects. */
export interface GraphEdge {
    /** Lane at the row's top boundary. */
    fromLane: number
    /** Lane at the row's bottom boundary. */
    toLane: number
    /** Palette index; keeps a branch one colour down its whole length. */
    color: number
}

/** Everything needed to paint the graph cell of a single commit row. */
export interface RowGraph {
    /** Lane the commit's node sits in. */
    nodeLane: number
    /** Lanes crossing this row untouched, drawn as straight verticals. */
    passThrough: GraphEdge[]
    /** Child lanes arriving from above and meeting the node. */
    incoming: GraphEdge[]
    /** The node's lines heading down to each parent's lane. */
    outgoing: GraphEdge[]
    /** Palette index for the node itself. */
    color: number
}

/** The graph for a whole (possibly partial) history page set. */
export interface CommitGraph {
    rows: RowGraph[]
    /** Widest the graph ever gets, so every row can reserve the same gutter. */
    laneCount: number
}

/** First empty slot, or one past the end when every lane is busy. */
function firstFreeLane(lanes: (string | null)[]): number {
    const free = lanes.indexOf(null)
    return free === -1 ? lanes.length : free
}

/**
 * Assigns lanes and edges for every revision in history order.
 *
 * A lane is "active" while some already-seen commit is still waiting for the
 * ancestor named in it. On reaching that ancestor the lane is freed and the
 * commit's own parents claim lanes in turn: the first parent inherits the
 * commit's lane (the branch continues straight), later parents open new lanes
 * (a merge), and a parent another lane is already heading to is joined rather
 * than duplicated (two branches converging).
 */
export function computeCommitGraph(
    revisions: Pick<Revision, 'id' | 'parents'>[],
): CommitGraph {
    // Index i holds the ancestor id lane i is travelling to, or null when idle.
    const lanes: (string | null)[] = []
    const rows: RowGraph[] = []
    let laneCount = 0

    const widen = (lane: number) => {
        if (lane + 1 > laneCount) laneCount = lane + 1
    }

    for (const revision of revisions) {
        const before = lanes.slice()

        // Every lane already aimed at this commit converges on its node; a merge
        // commit can have several. With none, this is a branch tip nothing in view
        // points at yet, so it opens a fresh lane and has no line from above.
        const incomingLanes: number[] = []
        for (let j = 0; j < before.length; j += 1) {
            if (before[j] === revision.id) incomingLanes.push(j)
        }
        const nodeLane =
            incomingLanes.length === 0
                ? firstFreeLane(lanes)
                : Math.min(...incomingLanes)
        for (const j of incomingLanes) lanes[j] = null
        widen(nodeLane)

        // Route the parents. The first one reclaims the node's lane so an ordinary
        // commit draws as one unbroken vertical; the rest branch out sideways.
        const outgoing: GraphEdge[] = []
        revision.parents.forEach((parentId, index) => {
            let lane = lanes.indexOf(parentId)
            if (lane === -1) {
                lane =
                    index === 0 && lanes[nodeLane] == null
                        ? nodeLane
                        : firstFreeLane(lanes)
                lanes[lane] = parentId
            }
            widen(lane)
            outgoing.push({fromLane: nodeLane, toLane: lane, color: lane})
        })

        // Lanes busy on entry that were not aimed at this commit simply cross it.
        const passThrough: GraphEdge[] = []
        for (let j = 0; j < before.length; j += 1) {
            if (before[j] != null && before[j] !== revision.id) {
                passThrough.push({fromLane: j, toLane: j, color: j})
                widen(j)
            }
        }

        const incoming: GraphEdge[] = incomingLanes.map((j) => ({
            fromLane: j,
            toLane: nodeLane,
            color: nodeLane,
        }))

        rows.push({nodeLane, passThrough, incoming, outgoing, color: nodeLane})
    }

    return {rows, laneCount}
}
