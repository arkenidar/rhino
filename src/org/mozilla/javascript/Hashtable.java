package org.mozilla.javascript;

import java.util.HashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * This generic hash table class is used by Set and Map. It uses
 * a standard HashMap for storing keys and values so that we can handle
 * lots of hash collisions if necessary, and a list to support the iterator
 * capability. This second one is important because JavaScript handling of
 * the iterator is completely different from the way that Java does it.
 */

public class Hashtable
{
    private final HashMap<Object, Entry> map = new HashMap<>();
    private Entry first = null;
    private Entry last = null;

    /**
     * One entry in the hash table. Override equals and hashcode because
     * this is another area in which JavaScript and Java differ. This entry
     * also becomes a node in the linked list.
     */
    static final class Entry {
        protected Object key;
        protected Object value;
        protected boolean deleted;
        protected Entry next;
        protected Entry prev;
        private final int hashCode;

        Entry() {
            hashCode = 0;
        }

        Entry(Object k, Object value) {
            if ((k instanceof Number) && ( ! ( k instanceof Double))) {
                // Hash comparison won't work if we don't do this
                this.key = ((Number)k).doubleValue();
            } else {
                this.key = k;
            }

            if (key == null) {
                hashCode = 0;
            } else if (k.equals(ScriptRuntime.negativeZero)) {
                hashCode = 0;
            } else {
                hashCode = key.hashCode();
            }

            this.value = value;
        }

        /**
         * Zero out key and value and return old value.
         */
        Object clear() {
            final Object ret = value;
            key = Undefined.instance;
            value = Undefined.instance;
            deleted = true;
            return ret;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object o) {
            try {
                return ScriptRuntime.sameZero(key, ((Entry)o).key);
            } catch (ClassCastException cce) {
                return false;
            }
        }
    }

    private Entry makeDummy() {
        final Entry d = new Entry();
        d.clear();
        return d;
    }

    public int size() {
        return map.size();
    }

    public void put(Object key, Object value) {
        final Entry nv = new Entry(key, value);
        final Entry ev = map.putIfAbsent(nv, nv);
        if (ev == null) {
            // New value -- insert to end of doubly-linked list
            if (first == null) {
                first = last = nv;
            } else {
                last.next = nv;
                nv.prev = last;
                last = nv;
            }
        } else {
            // Update the existing value and keep it in the same place in the list
            ev.value = value;
        }
    }

    public Object get(Object key) {
        final Entry e = new Entry(key, null);
        final Entry v = map.get(e);
        if (v == null) {
            return null;
        }
        return v.value;
    }

    public boolean has(Object key) {
        final Entry e = new Entry(key, null);
        return map.containsKey(e);
    }

    public Object delete(Object key) {
        final Entry e = new Entry(key, null);
        final Entry v = map.remove(e);
        if (v == null) {
            return null;
        }

        // To keep existing iterators moving forward as specified in EC262,
        // we will remove the "prev" pointers from the list but leave the "next"
        // pointers intact. Once we do that, then the only things pointing to
        // the deleted notes are existing iterators. Once those are gone, then
        // these objects will be GCed.
        if (v == first) {
            if (v == last) {
                // Removing the only element. Leave it as a dummy or existing iterators
                // will never stop.
                v.clear();
                v.prev = null;
            } else {
                first = v.next;
                first.prev = null;
                if (first.next != null) {
                    first.next.prev = first;
                }
            }
        } else {
            final Entry prev = v.prev;
            prev.next = v.next;
            v.prev = null;
            if (v.next != null) {
                v.next.prev = prev;
            } else {
                assert(v == last);
                last = prev;
            }
        }
        // Still clear the node in case it is in the chain of some iterator
        return v.clear();
    }

    public void clear() {
        // Zero out all the entries so that existing iterators will skip them all
        Iterator<Entry> it = iterator();
        it.forEachRemaining(Entry::clear);

        // Replace the existing list with a dummy, and make it the last node
        // of the current list. If new nodes are added now, existing iterators
        // will drive forward right into the new list. If they are not, then
        // nothing is referencing the old list and it'll get GCed.
        if (first != null) {
            Entry dummy = new Entry();
            dummy.clear();
            last.next = dummy;
            first = last = dummy;
        }

        // Now we can clear the actual hashtable!
        map.clear();
    }

    public Iterator<Entry> iterator() {
        return new Iter(first);
    }

    // The iterator for this class works directly on the linked list so that it implements
    // the specified iteration behavior, which is very different from Java.
    private final class Iter
        implements Iterator<Entry>
    {
        private Entry pos;

        Iter(Entry start) {
            // Keep the logic simpler by having a dummy at the start
            Entry dummy = makeDummy();
            dummy.next = start;
            this.pos = dummy;
        }

        @Override
        public boolean hasNext() {
            // Skip forward past deleted elements, which could appear due to
            // "delete" or a "clear" operation after this iterator was created.
            // End up just before the next non-deleted node.
            while ((pos.next != null) && pos.next.deleted) {
                pos = pos.next;
            }
            return (pos.next != null);
        }

        @Override
        public Entry next() {
            if ((pos == null) || (pos.next == null)) {
                throw new NoSuchElementException();
            }
            final Entry e = pos.next;
            pos = pos.next;
            return e;
        }
    }
}
