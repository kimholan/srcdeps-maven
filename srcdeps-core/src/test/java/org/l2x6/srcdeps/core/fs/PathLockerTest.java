/**
 * Copyright 2015-2016 Maven Source Dependencies
 * Plugin contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.l2x6.srcdeps.core.fs;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.Test;
import org.l2x6.srcdeps.core.shell.Shell;
import org.l2x6.srcdeps.core.shell.ShellCommand;
import org.l2x6.srcdeps.core.util.SrcdepsCoreUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author <a href="https://github.com/ppalaga">Peter Palaga</a>
 */
public class PathLockerTest {
    private static final Logger log = LoggerFactory.getLogger(PathLockerTest.class);
    private static final String pid = ManagementFactory.getRuntimeMXBean().getName();

    private static final Path targetDirectory = Paths.get(System.getProperty("project.build.directory", "target"))
            .toAbsolutePath();
    private static final Path lockerDirectory;

    static {
        lockerDirectory = targetDirectory.resolve("pathLocker");
    }

    public static String getJarPath(Class<?> c) {
        URL url = c.getResource(c.getSimpleName() + ".class");
        Assert.assertEquals("jar", url.getProtocol());
        String path = url.getPath();
        Assert.assertTrue(path.startsWith("file:"));
        path = path.substring("file:".length());
        int exclMarkPos = path.lastIndexOf('!');
        Assert.assertTrue(exclMarkPos > 1);
        path = path.substring(0, exclMarkPos);
        return path;
    }

    /**
     * First spawns another process that locks some test directory and then makes sure that the present process'es
     * {@link PathLocker} cannot lock the same directory at the same time.
     *
     * @throws Exception
     */
    @Test
    public void anotherProcess() throws Exception {

        final Path keepRunnigFile = targetDirectory
                .resolve("PathLockerProcess-keep-running-" + UUID.randomUUID().toString());
        Files.write(keepRunnigFile, "PathLockerProcess will run till this file exists".getBytes("utf-8"));

        final Path dirToLock = lockerDirectory.resolve(UUID.randomUUID().toString());
        final Path lockSuccessFile = dirToLock.resolve("lock-success.txt");

        /* lock dirToLock from another process running on the same machine */
        final String slfApiJar = getJarPath(org.slf4j.LoggerFactory.class);
        final String slfSimpleJar = getJarPath(org.slf4j.impl.StaticLoggerBinder.class);
        final String classPath = targetDirectory.resolve("classes").toString() + File.pathSeparator
                + targetDirectory.resolve("test-classes").toString() + File.pathSeparator + slfApiJar
                + File.pathSeparator + slfSimpleJar;
        final ShellCommand command = ShellCommand.builder().executable(SrcdepsCoreUtils.getCurrentJavaExecutable())
                .arguments("-cp", classPath, PathLockerProcess.class.getName(), dirToLock.toString(),
                        keepRunnigFile.toString(), lockSuccessFile.toString())
                .workingDirectory(lockerDirectory).build();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> future = executor.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                Shell.execute(command).assertSuccess();
                return true;
            }
        });

        /* with some delay, the dirToLock eventually gets locked by PathLockerProcess */
        final PathLocker pathLocker = new PathLocker();
        final long timeoutSeconds = 5;
        final long lockDeadline = System.currentTimeMillis() + (timeoutSeconds * 1000);
        while (Files.notExists(lockSuccessFile) && System.currentTimeMillis() <= lockDeadline) {
            log.debug(pid + " Lock success file not there yet {}", lockSuccessFile);
            Thread.sleep(10);
        }

        Assert.assertTrue(
                String.format("PathLockerProcess has not locked [%s] within [%d] seconds", dirToLock, timeoutSeconds),
                Files.exists(lockSuccessFile));

        log.debug(pid + " Lock success file exists {}", lockSuccessFile);

        try (PathLock lock1 = pathLocker.tryLockDirectory(dirToLock)) {
            /* locked for the current thread */
            Assert.fail(String.format("The current thread and process should not be able to lock [%s]", dirToLock));
        } catch (CannotAcquireLockException e) {
            /* expected */
        } finally {
            /* Signal to PathLockerProcess that it should exit */
            Files.deleteIfExists(keepRunnigFile);

            /* Ensure the PathLockerProcess exited cleanly */
            future.get(5, TimeUnit.SECONDS);
        }

        /* PathLockerProcess must have unlocked at this point
         * and we must suceed in locking from here now */
        try (PathLock lock1 = pathLocker.tryLockDirectory(dirToLock)) {
            Assert.assertTrue(lock1 != null);
        }

    }

    /**
     * Makes sure that multiple threads of the same Java process cannot lock the same directory at once.
     *
     * @throws Exception
     */
    @Test
    public void multipleThreadsOfCurrentJvm() throws Exception {

        final PathLocker pathLocker = new PathLocker();

        final Path dir1 = lockerDirectory.resolve(UUID.randomUUID().toString());

        try (PathLock lock1 = pathLocker.tryLockDirectory(dir1)) {
            /* locked for the current thread */

            /* now try to lock from another thread which should fail with a CannotAcquireLockException */

            try {
                lockConcurrently(pathLocker, dir1).get(5, TimeUnit.SECONDS);
                Assert.fail("CannotAcquireLockException expected");
            } catch (InterruptedException e) {
                throw e;
            } catch (ExecutionException e) {
                Assert.assertTrue("Should throw CannotAcquireLockException",
                        CannotAcquireLockException.class.equals(e.getCause().getClass()));
            } catch (TimeoutException e) {
                throw e;
            }
        }

        /* unlocked again */
        Assert.assertTrue(lockConcurrently(pathLocker, dir1).get(5, TimeUnit.SECONDS));

    }

    private Future<Boolean> lockConcurrently(final PathLocker pathLocker, final Path path) {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        return executor.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                pathLocker.tryLockDirectory(path);
                return true;
            }
        });
    }

}