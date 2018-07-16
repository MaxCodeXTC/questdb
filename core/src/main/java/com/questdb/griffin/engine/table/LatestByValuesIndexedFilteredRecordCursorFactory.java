/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2018 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.griffin.engine.table;

import com.questdb.cairo.sql.DataFrameCursorFactory;
import com.questdb.cairo.sql.Function;
import com.questdb.cairo.sql.RecordMetadata;
import com.questdb.std.CharSequenceHashSet;
import com.questdb.std.IntHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LatestByValuesIndexedFilteredRecordCursorFactory extends AbstractDeferredTreeSetRecordCursorFactory {

    public LatestByValuesIndexedFilteredRecordCursorFactory(
            @NotNull RecordMetadata metadata,
            @NotNull DataFrameCursorFactory dataFrameCursorFactory,
            int columnIndex,
            @NotNull IntHashSet symbolKeys,
            @Nullable Function filter,
            @Nullable CharSequenceHashSet deferredSymbols) {
        super(metadata, dataFrameCursorFactory, columnIndex, symbolKeys, deferredSymbols);
        if (filter != null) {
            this.cursor = new LatestByValuesIndexedFilteredRecordCursor(columnIndex, treeSet, symbolKeys, filter);
        } else {
            this.cursor = new LatestByValuesIndexedRecordCursor(columnIndex, treeSet, symbolKeys);
        }
    }
}