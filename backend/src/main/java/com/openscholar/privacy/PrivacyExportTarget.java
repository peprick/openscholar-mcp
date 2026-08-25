package com.openscholar.privacy;

import java.io.IOException;
import java.io.OutputStream;

@FunctionalInterface
public interface PrivacyExportTarget {

	OutputStream open(long contentLength) throws IOException;
}
