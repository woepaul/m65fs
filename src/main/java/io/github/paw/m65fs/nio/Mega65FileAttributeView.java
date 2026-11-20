package io.github.paw.m65fs.nio;

import java.io.IOException;
import java.nio.file.attribute.DosFileAttributeView;

/** Attribute view named {@code "mega65"}: the DOS view plus {@link Mega65FileAttributes}. */
public interface Mega65FileAttributeView extends DosFileAttributeView {

  @Override
  default String name() {
    return "mega65";
  }

  @Override
  Mega65FileAttributes readAttributes() throws IOException;
}
