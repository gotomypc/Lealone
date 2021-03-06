/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.codefollower.lealone.mvstore.dbobject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;

import com.codefollower.lealone.constant.ErrorCode;
import com.codefollower.lealone.dbobject.index.BaseIndex;
import com.codefollower.lealone.dbobject.index.Cursor;
import com.codefollower.lealone.dbobject.index.IndexType;
import com.codefollower.lealone.dbobject.table.Column;
import com.codefollower.lealone.dbobject.table.IndexColumn;
import com.codefollower.lealone.engine.Database;
import com.codefollower.lealone.engine.Session;
import com.codefollower.lealone.message.DbException;
import com.codefollower.lealone.mvstore.MVMap;
import com.codefollower.lealone.result.Row;
import com.codefollower.lealone.result.SearchRow;
import com.codefollower.lealone.result.SortOrder;
import com.codefollower.lealone.util.New;
import com.codefollower.lealone.value.Value;
import com.codefollower.lealone.value.ValueLong;
import com.codefollower.lealone.value.ValueNull;

/**
 * A table stored in a MVStore.
 */
public class MVSecondaryIndex extends BaseIndex {

    /**
     * The multi-value table.
     */
    final MVTable mvTable;

    private final int keyColumns;
    private MVMap<Value[], Long> map;

    public MVSecondaryIndex(Database db, MVTable table, int id, String indexName,
                IndexColumn[] columns, IndexType indexType) {
        this.mvTable = table;
        initBaseIndex(table, id, indexName, columns, indexType);
        if (!database.isStarting()) {
            checkIndexColumnTypes(columns);
        }
        // always store the row key in the map key,
        // even for unique indexes, as some of the index columns could be null
        keyColumns = columns.length + 1;
        int[] sortTypes = new int[keyColumns];
        for (int i = 0; i < columns.length; i++) {
            sortTypes[i] = columns[i].sortType;
        }
        sortTypes[keyColumns - 1] = SortOrder.ASCENDING;
        String name = getName() + "_" + getId();
        ValueArrayDataType t = new ValueArrayDataType(
                db.getCompareMode(), db, sortTypes);
        map = table.getStore().openMap(name,
                new MVMap.Builder<Value[], Long>().keyType(t));

    }

    private static void checkIndexColumnTypes(IndexColumn[] columns) {
        for (IndexColumn c : columns) {
            int type = c.column.getType();
            if (type == Value.CLOB || type == Value.BLOB) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1, "Index on BLOB or CLOB column: " + c.column.getCreateSQL());
            }
        }
    }

    @Override
    public void close(Session session) {
        // ok
    }

    public void rename(String newName) {
        map.renameMap(newName + "_" + getId());
        super.rename(newName);
    }

    @Override
    public void add(Session session, Row row) {
        Value[] array = getKey(row);
        if (indexType.isUnique()) {
            array[keyColumns - 1] = ValueLong.get(0);
            Value[] key = map.ceilingKey(array);
            if (key != null) {
                SearchRow r2 = getRow(key);
                if (compareRows(row, r2) == 0) {
                    if (!containsNullAndAllowMultipleNull(r2)) {
                        throw getDuplicateKeyException();
                    }
                }
            }
        }
        array[keyColumns - 1] = ValueLong.get(row.getKey());
        map.put(array, Long.valueOf(0));
    }

    @Override
    public void remove(Session session, Row row) {
        Value[] array = getKey(row);
        Long old = map.remove(array);
        if (old == null) {
            if (old == null) {
                throw DbException.get(ErrorCode.ROW_NOT_FOUND_WHEN_DELETING_1,
                        getSQL() + ": " + row.getKey());
            }
        }
    }

    @Override
    public Cursor find(Session session, SearchRow first, SearchRow last) {
        Value[] min = getKey(first);
        return new MVStoreCursor(session, map.keyIterator(min), last);
    }

    private Value[] getKey(SearchRow r) {
        if (r == null) {
            return null;
        }
        Value[] array = new Value[keyColumns];
        for (int i = 0; i < columns.length; i++) {
            Column c = columns[i];
            int idx = c.getColumnId();
            if (r != null) {
                array[i] = r.getValue(idx);
            }
        }
        array[keyColumns - 1] = ValueLong.get(r.getKey());
        return array;
    }

    /**
     * Get the row with the given index key.
     *
     * @param array the index key
     * @return the row
     */
    SearchRow getRow(Value[] array) {
        SearchRow searchRow = mvTable.getTemplateRow();
        searchRow.setKey((array[array.length - 1]).getLong());
        Column[] cols = getColumns();
        for (int i = 0; i < array.length - 1; i++) {
            Column c = cols[i];
            int idx = c.getColumnId();
            Value v = array[i];
            searchRow.setValue(idx, v);
        }
        return searchRow;
    }

    public MVTable getTable() {
        return mvTable;
    }

    @Override
    public double getCost(Session session, int[] masks) {
        return 10 * getCostRangeIndex(masks, map.getSize());
    }

    @Override
    public void remove(Session session) {
        if (!map.isClosed()) {
            map.removeMap();
        }
    }

    @Override
    public void truncate(Session session) {
        map.clear();
    }

    @Override
    public boolean canGetFirstOrLast() {
        return true;
    }

    @Override
    public Cursor findFirstOrLast(Session session, boolean first) {
        Value[] key = first ? map.firstKey() : map.lastKey();
        while (true) {
            if (key == null) {
                return new MVStoreCursor(session, Collections.<Value[]>emptyList().iterator(), null);
            }
            if (key[0] != ValueNull.INSTANCE) {
                break;
            }
            key = first ? map.higherKey(key) : map.lowerKey(key);
        }
        ArrayList<Value[]> list = New.arrayList();
        list.add(key);
        MVStoreCursor cursor = new MVStoreCursor(session, list.iterator(), null);
        cursor.next();
        return cursor;
    }

    @Override
    public boolean needRebuild() {
        return map.getSize() == 0;
    }

    @Override
    public long getRowCount(Session session) {
        return map.getSize();
    }

    @Override
    public long getRowCountApproximation() {
        return map.getSize();
    }

    public long getDiskSpaceUsed() {
        // TODO estimate disk space usage
        return 0;
    }

    @Override
    public void checkRename() {
        // ok
    }

    /**
     * A cursor.
     */
    class MVStoreCursor implements Cursor {

        private final Session session;
        private final Iterator<Value[]> it;
        private final SearchRow last;
        private Value[] current;
        private SearchRow searchRow;
        private Row row;

        public MVStoreCursor(Session session, Iterator<Value[]> it, SearchRow last) {
            this.session = session;
            this.it = it;
            this.last = last;
        }

        @Override
        public Row get() {
            if (row == null) {
                SearchRow r = getSearchRow();
                if (r != null) {
                    row = mvTable.getRow(session, r.getKey());
                }
            }
            return row;
        }

        @Override
        public SearchRow getSearchRow() {
            if (searchRow == null) {
                if (current != null) {
                    searchRow = getRow(current);
                }
            }
            return searchRow;
        }

        @Override
        public boolean next() {
            current = it.next();
            searchRow = null;
            if (current != null) {
                if (last != null && compareRows(getSearchRow(), last) > 0) {
                    searchRow = null;
                    current = null;
                }
            }
            row = null;
            return current != null;
        }

        @Override
        public boolean previous() {
            // TODO previous
            return false;
        }

    }

}
