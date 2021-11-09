/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.cairo.CairoException;
import io.questdb.cairo.TableReader;
import io.questdb.cairo.TableWriter;
import io.questdb.mp.MPSequence;
import io.questdb.mp.SCSequence;
import io.questdb.std.Chars;
import io.questdb.std.FilesFacadeImpl;
import io.questdb.std.str.LPSZ;
import io.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import static io.questdb.griffin.AlterCommandExecution.*;

public class TableWriterAsyncCmdTest extends AbstractGriffinTest {

    private final SCSequence commandReplySequence = new SCSequence();
    private final int engineCmdQueue = engine.getConfiguration().getWriterCommandQueueCapacity();
    private final int engineEventQueue = engine.getConfiguration().getWriterCommandQueueCapacity();

    @Test
    public void testAsyncAlterCommandsExceedEngineEventQueue() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table product (timestamp timestamp)", sqlExecutionContext);

            // Block event queue with stale sequence
            SCSequence staleSequence = new SCSequence();
            setUpEngineAsyncWriterEventWait(engine, staleSequence);

            try (TableWriter writer = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "product", "test lock")) {
                for (int i = 0; i < engineEventQueue; i++) {
                    CompiledQuery cc = compiler.compile("ALTER TABLE product add column column" + i + " int", sqlExecutionContext);
                    executeAlterCommandNoWait(engine, cc.getAlterStatement(), sqlExecutionContext);
                    engine.tick();
                    writer.tick();
                }

                // Add column when event queue is stalled
                CompiledQuery cc = compiler.compile("ALTER TABLE product add column column5 int", sqlExecutionContext);
                setUpEngineAsyncWriterEventWait(engine, commandReplySequence);
                long commandId = executeAlterCommandNoWait(engine, cc.getAlterStatement(), sqlExecutionContext);
                engine.tick();
                writer.tick();
                SqlException exception = waitWriterEvent(engine, commandId, commandReplySequence, 500_000, 0);
                TestUtils.assertContains(exception.getFlyweightMessage(), "Timeout expired");

                // Remove sequence
                stopEngineAsyncWriterEventWait(engine, staleSequence);

                // Re-execute last query
                try {
                    setUpEngineAsyncWriterEventWait(engine, commandReplySequence);
                    commandId = executeAlterCommandNoWait(engine, cc.getAlterStatement(), sqlExecutionContext);
                    engine.tick();
                    writer.tick();

                    exception = waitWriterEvent(engine, commandId, commandReplySequence, 500_000, 0);
                    TestUtils.assertContains(exception.getFlyweightMessage(), "Duplicate column name: column5");
                } finally {
                    stopEngineAsyncWriterEventWait(engine, commandReplySequence);
                }
            }
        });
    }

    @Test
    public void testAsyncAlterCommandsExceedsEngineCmdQueue() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table product (timestamp timestamp)", sqlExecutionContext);

            // Block table
            try (TableWriter ignored = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "product", "test lock")) {

                for (int i = 0; i < engineCmdQueue; i++) {
                    CompiledQuery cc = compiler.compile("ALTER TABLE product add column column" + i + " int", sqlExecutionContext);
                    executeAlterCommandNoWait(engine, cc.getAlterStatement(), sqlExecutionContext);
                    engine.tick();
                }

                try {
                    CompiledQuery cc = compiler.compile("ALTER TABLE product add column column5 int", sqlExecutionContext);
                    executeAlterCommandNoWait(engine, cc.getAlterStatement(), sqlExecutionContext);
                    Assert.fail();
                } catch (CairoException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "Could not publish writer ALTER TABLE task [table=product]");
                }
            } // Unblock table

            CompiledQuery cc = compiler.compile("ALTER TABLE product add column column5 int", sqlExecutionContext);
            Assert.assertEquals(-1, executeAlterCommandNoWait(engine, cc.getAlterStatement(), sqlExecutionContext));
        });
    }

    @Test
    public void testAsyncAlterCommandsFailsToRemoveColumn() throws Exception {
        assertMemoryLeak(() -> {
            ff = new FilesFacadeImpl() {
                int attempt = 0;

                @Override
                public boolean rename(LPSZ from, LPSZ to) {
                    if (Chars.endsWith(from, "_meta") && attempt++ < configuration.getFileOperationRetryCount()) {
                        return false;
                    }
                    return super.rename(from, to);
                }
            };
            compile("create table product as (select x, x as toremove from long_sequence(100))", sqlExecutionContext);
            long commandId;

            // Block table
            try (TableWriter ignored = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "product", "test lock")) {
                CompiledQuery cc = compiler.compile("ALTER TABLE product drop column toremove", sqlExecutionContext);
                commandId = executeAlterCommandNoWait(engine, cc.getAlterStatement(), sqlExecutionContext);
                engine.tick();
            } // Unblock table

            SqlException exception = waitWriterEvent(engine, commandId, commandReplySequence, 500_000, 0);
            Assert.assertNotNull(exception);
            TestUtils.assertContains(exception.getFlyweightMessage(), "cannot drop column. Try again later");
            compile("ALTER TABLE product drop column toremove", sqlExecutionContext);
        });
    }

    @Test
    public void testAsyncAlterCommandsFailsToDropPartition() throws Exception {
        assertMemoryLeak(() -> {
            ff = new FilesFacadeImpl() {
                int attempt = 0;

                @Override
                public boolean rename(LPSZ from, LPSZ to) {
                    if (Chars.endsWith(from, "_meta") && attempt++ < configuration.getFileOperationRetryCount()) {
                        return false;
                    }
                    return super.rename(from, to);
                }
            };
            compile("create table product as (select x, x as toremove from long_sequence(100))", sqlExecutionContext);
            long commandId;

            // Block table
            try (TableWriter ignored = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "product", "test lock")) {
                CompiledQuery cc = compiler.compile("ALTER TABLE product drop column toremove", sqlExecutionContext);
                commandId = executeAlterCommandNoWait(engine, cc.getAlterStatement(), sqlExecutionContext);
                engine.tick();
            } // Unblock table

            SqlException exception = waitWriterEvent(engine, commandId, commandReplySequence, 500_000, 0);
            Assert.assertNotNull(exception);
            TestUtils.assertContains(exception.getFlyweightMessage(), "cannot drop column. Try again later");
            compile("ALTER TABLE product drop column toremove", sqlExecutionContext);
        });
    }

    @Test
    public void testAsyncAlterNonExistingTable() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table product (timestamp timestamp, name symbol nocache)", sqlExecutionContext);
            try {
                setUpEngineAsyncWriterEventWait(engine, commandReplySequence);
                long commandId;
                try (TableWriter writer = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "product", "test lock")) {
                    AlterStatement creepyAlter = new AlterStatement();
                    creepyAlter.ofDropColumn(1, "product", writer.getMetadata().getId());
                    creepyAlter.ofDropColumn("timestamp");
                    commandId = executeAlterCommandNoWait(engine, creepyAlter, sqlExecutionContext);
                }
                compile("drop table product", sqlExecutionContext);
                engine.tick();

                // ALTER TABLE should be executed successfully on writer.close() before engine.tick()
                SqlException exception = waitWriterEvent(engine, commandId, commandReplySequence, 500_000, 0);
                Assert.assertNull(exception);
            } finally {
                stopEngineAsyncWriterEventWait(engine, commandReplySequence);
            }
        });
    }

    @Test
    public void testAsyncAlterSymbolCache() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table product (timestamp timestamp, name symbol nocache)", sqlExecutionContext);
            try {
                setUpEngineAsyncWriterEventWait(engine, commandReplySequence);
                long commandId;
                try (TableWriter writer = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "product", "test lock")) {
                    CompiledQuery cc = compiler.compile("alter table product alter column name cache", sqlExecutionContext);
                    commandId = executeAlterCommandNoWait(engine, cc.getAlterStatement(), sqlExecutionContext);
                    writer.tick();
                    engine.tick();
                }

                SqlException exception = waitWriterEvent(engine, commandId, commandReplySequence, 500_000, 0);
                Assert.assertNull(exception);

                engine.releaseAllReaders();
                try (TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "product")) {
                    int colIndex = rdr.getMetadata().getColumnIndex("name");
                    Assert.assertTrue(rdr.getSymbolMapReader(colIndex).isCached());
                }
            } finally {
                stopEngineAsyncWriterEventWait(engine, commandReplySequence);
            }
        });
    }

    @Test
    public void testAsyncRenameMultipleColumns() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table product (timestamp timestamp, name symbol nocache)", sqlExecutionContext);
            try {
                setUpEngineAsyncWriterEventWait(engine, commandReplySequence);
                long commandId;
                try (TableWriter ignored = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "product", "test lock")) {
                    // Add invalid command to engine queue
                    MPSequence commandPugSeq = messageBus.getTableWriterCommandPubSeq();
                    long pubCursor = commandPugSeq.next();
                    Assert.assertTrue(pubCursor > -1);
                    messageBus.getTableWriterCommandQueue().get(pubCursor).setTableId(ignored.getMetadata().getId());
                    commandPugSeq.done(pubCursor);

                    CompiledQuery cc = compiler.compile("alter table product rename column name to name1, timestamp to timestamp1", sqlExecutionContext);
                    commandId = executeAlterCommandNoWait(engine, cc.getAlterStatement(), sqlExecutionContext);
                }
                engine.tick();

                SqlException exception = waitWriterEvent(engine, commandId, commandReplySequence, 500_000, 0);
                Assert.assertNull(exception);

                engine.releaseAllReaders();
                try (TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "product")) {
                    Assert.assertEquals(0, rdr.getMetadata().getColumnIndex("timestamp1"));
                    Assert.assertEquals(1, rdr.getMetadata().getColumnIndex("name1"));
                }
            } finally {
                stopEngineAsyncWriterEventWait(engine, commandReplySequence);
            }
        });
    }

    @Test
    public void testCommandQueueReused() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table product (timestamp timestamp)", sqlExecutionContext);

            // Block event queue with stale sequence
            try (TableWriter writer = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "product", "test lock")) {
                try {
                    setUpEngineAsyncWriterEventWait(engine, commandReplySequence);
                    for (int i = 0; i < 2 * engineEventQueue; i++) {
                        CompiledQuery cc = compiler.compile("ALTER TABLE product add column column" + i + " int", sqlExecutionContext);
                        long commandId = executeAlterCommandNoWait(engine, cc.getAlterStatement(), sqlExecutionContext);
                        engine.tick();
                        writer.tick();
                        Assert.assertNull(waitWriterEvent(engine, commandId, commandReplySequence, 500_000, 0));
                    }

                    Assert.assertEquals(2L * engineEventQueue + 1, writer.getMetadata().getColumnCount());
                } finally {
                    stopEngineAsyncWriterEventWait(engine, commandReplySequence);
                }
            }
        });
    }

    @Test
    public void testInvalidAlterDropPartitionStatementQueued() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table product (timestamp timestamp, name symbol nocache)", sqlExecutionContext);

            try (TableWriter writer = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "product", "test lock")) {
                try {
                    setUpEngineAsyncWriterEventWait(engine, commandReplySequence);

                    AlterStatement creepyAlter = new AlterStatement();
                    creepyAlter.ofDropPartition(0, "product", writer.getMetadata().getId()).ofPartition(0);
                    long commandId = executeAlterCommandNoWait(engine, creepyAlter, sqlExecutionContext);
                    engine.tick();
                    writer.tick();

                    SqlException exception = waitWriterEvent(engine, commandId, commandReplySequence, 500_000, 0);
                    Assert.assertNotNull(exception);
                    TestUtils.assertContains(exception.getFlyweightMessage(), "could not remove partition 'default'");
                } finally {
                    stopEngineAsyncWriterEventWait(engine, commandReplySequence);
                }
            }
        });
    }

    @Test
    public void testInvalidAlterStatementQueued() throws Exception {
        assertMemoryLeak(() -> {
            compile("create table product (timestamp timestamp, name symbol nocache)", sqlExecutionContext);

            try (TableWriter writer = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "product", "test lock")) {
                try {
                    setUpEngineAsyncWriterEventWait(engine, commandReplySequence);

                    AlterStatement creepyAlter = new AlterStatement();
                    creepyAlter.ofDropColumn(1, "product", writer.getMetadata().getId());
                    creepyAlter.ofDropColumn("timestamp").ofDropColumn("timestamp");
                    long commandId = executeAlterCommandNoWait(engine, creepyAlter, sqlExecutionContext);
                    engine.tick();
                    writer.tick(true);

                    SqlException exception = waitWriterEvent(engine, commandId, commandReplySequence, 500_000, 0);
                    Assert.assertNotNull(exception);
                    TestUtils.assertContains(exception.getFlyweightMessage(), "Invalid column: timestamp");
                } finally {
                    stopEngineAsyncWriterEventWait(engine, commandReplySequence);
                }
            }
        });
    }

}
