package com.openscholar.access.internal.provider;

public interface AccessEvidenceProvider {

	AccessSource source();

	AccessEvidenceResult resolve(AccessEvidenceLookup lookup);
}
