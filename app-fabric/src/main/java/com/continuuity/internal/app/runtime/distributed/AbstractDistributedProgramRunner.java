/*
 * Copyright 2012-2013 Continuuity,Inc. All Rights Reserved.
 */
package com.continuuity.internal.app.runtime.distributed;

import com.continuuity.app.program.Program;
import com.continuuity.app.runtime.ProgramController;
import com.continuuity.app.runtime.ProgramOptions;
import com.continuuity.app.runtime.ProgramRunner;
import com.continuuity.common.conf.CConfiguration;
import com.continuuity.internal.app.program.ForwardingProgram;
import com.continuuity.weave.api.WeaveApplication;
import com.continuuity.weave.api.WeaveController;
import com.continuuity.weave.api.WeavePreparer;
import com.continuuity.weave.api.WeaveRunner;
import com.continuuity.weave.api.logging.PrinterLogHandler;
import com.continuuity.weave.common.ServiceListenerAdapter;
import com.continuuity.weave.common.Threads;
import com.continuuity.weave.filesystem.LocalLocationFactory;
import com.continuuity.weave.filesystem.Location;
import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.io.Files;
import com.google.common.io.InputSupplier;
import com.google.common.util.concurrent.Service;
import com.google.gson.Gson;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Defines the base framework for starting {@link Program} in the cluster.
 */
public abstract class AbstractDistributedProgramRunner implements ProgramRunner {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractDistributedProgramRunner.class);

  private final WeaveRunner weaveRunner;
  private final Configuration hConf;
  private final CConfiguration cConf;

  /**
   * An interface for launching WeaveApplication. Used by sub-classes only.
   */
  protected interface ApplicationLauncher {
    WeaveController launch(WeaveApplication weaveApplication, Class<?>... dependencies);
  }

  protected AbstractDistributedProgramRunner(WeaveRunner weaveRunner, Configuration hConf, CConfiguration cConf) {
    this.weaveRunner = weaveRunner;
    this.hConf = hConf;
    this.cConf = cConf;
  }

  @Override
  public final ProgramController run(final Program program, ProgramOptions options) {
    final File hConfFile;
    final File cConfFile;
    final Program copiedProgram;
    try {
      // Copy config files and program jar to local temp, and ask Weave to localize it to container.
      // What Weave does is to save those files in HDFS and keep using them during the lifetime of application.
      // Weave will manage the cleanup of those files in HDFS.
      hConfFile = saveHConf(hConf, File.createTempFile("hConf", ".xml"));
      cConfFile = saveCConf(cConf, File.createTempFile("cConf", ".xml"));
      copiedProgram = copyProgramJar(program);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }

    final String runtimeArgs = new Gson().toJson(options.getUserArguments());
    return launch(copiedProgram, options, hConfFile, cConfFile, new ApplicationLauncher() {
      @Override
      public WeaveController launch(WeaveApplication weaveApplication, Class<?>... dependencies) {
        WeavePreparer preparer = weaveRunner
          .prepare(weaveApplication)
          .addLogHandler(new PrinterLogHandler(new PrintWriter(System.out)))
          .withApplicationArguments(
            String.format("--%s", RunnableOptions.JAR), copiedProgram.getJarLocation().getName(),
            String.format("--%s", RunnableOptions.RUNTIME_ARGS), runtimeArgs
          );
        if (dependencies.length > 0) {
          preparer = preparer.withDependencies(dependencies);
        }
        return addCleanupListener(preparer.start(), hConfFile, cConfFile, copiedProgram);
      }
    });
  }

  /**
   * Sub-class overrides this method to launch the weave application.
   */
  protected abstract ProgramController launch(Program program, ProgramOptions options,
                                              File hConfFile, File cConfFile, ApplicationLauncher launcher);


  private File saveHConf(Configuration conf, File file) throws IOException {
    Writer writer = Files.newWriter(file, Charsets.UTF_8);
    try {
      conf.writeXml(writer);
    } finally {
      writer.close();
    }
    return file;
  }

  private File saveCConf(CConfiguration conf, File file) throws IOException {
    Writer writer = Files.newWriter(file, Charsets.UTF_8);
    try {
      conf.writeXml(writer);
    } finally {
      writer.close();
    }
    return file;
  }

  /**
   * Copies the program jar to a local temp file and return a {@link Program} instance
   * with {@link Program#getJarLocation()} points to the local temp file.
   */
  private Program copyProgramJar(final Program program) throws IOException {
    File tempJar = File.createTempFile(program.getName(), ".jar");
    Files.copy(new InputSupplier<InputStream>() {
      @Override
      public InputStream getInput() throws IOException {
        return program.getJarLocation().getInputStream();
      }
    }, tempJar);

    final Location jarLocation = new LocalLocationFactory().create(tempJar.toURI());

    return new ForwardingProgram(program) {
      @Override
      public Location getJarLocation() {
        return jarLocation;
      }
    };
  }

  /**
   * Adds a listener to the given WeaveController to delete local temp files when the program has started/terminated.
   * The local temp files could be removed once the program is started, since Weave would keep the files in
   * HDFS and no long needs the local temp files once program is started.
   *
   * @return The same WeaveController instance.
   */
  private WeaveController addCleanupListener(WeaveController controller, final File hConfFile,
                                             final File cConfFile, final Program program) {

    final AtomicBoolean deleted = new AtomicBoolean(false);
    controller.addListener(new ServiceListenerAdapter() {
      @Override
      public void running() {
        cleanup();
      }

      @Override
      public void terminated(Service.State from) {
        cleanup();
      }

      @Override
      public void failed(Service.State from, Throwable failure) {
        cleanup();
      }

      private void cleanup() {
        if (deleted.compareAndSet(false, true)) {
          LOG.debug("Cleanup tmp files for {}: {} {} {}",
                    program.getName(), hConfFile, cConfFile, program.getJarLocation().toURI());
          hConfFile.delete();
          cConfFile.delete();
          try {
            program.getJarLocation().delete();
          } catch (IOException e) {
            LOG.warn("Failed to delete program jar {}", program.getJarLocation().toURI(), e);
          }
        }
      }
    }, Threads.SAME_THREAD_EXECUTOR);
    return controller;
  }
}
