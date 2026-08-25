import type { OfflineCollectionPack } from "@/shared/api/library-schemas";

export type OfflinePackInspection = Readonly<{
  formatVersion: 1;
  cryptoProfile: "pbkdf2-sha256-aes256gcm-v1";
  ownerScope: string;
  collectionDigest: string;
}>;

export type OfflinePackEvent = "LOCK" | "PURGE" | "REPLACED";

export type OfflinePackSaveFence = Readonly<{
  collectionDigest: string;
  lifecycleEpoch: string;
  ownerScope: string;
}>;

export type OfflinePackDeletionFence = Readonly<{
  collectionDigest: string | null;
  deletionId: string;
}>;

export interface OpenScholarOfflinePackRuntime {
  readonly constants: Readonly<{
    formatVersion: 1;
    readerRevision: "2026-08-24-r4";
    cryptoProfile: "pbkdf2-sha256-aes256gcm-v1";
    workFactor: 600000;
    maximumPapers: 500;
    maximumPlaintextBytes: 1048576;
    minimumPassphraseCharacters: 12;
    maximumPassphraseCharacters: 128;
    maximumPassphraseBytes: 256;
  }>;
  prepareSave(
    collectionId: string,
    ownerScope: string,
  ): Promise<OfflinePackSaveFence>;
  save(
    payload: OfflineCollectionPack,
    passphrase: string,
    fence: OfflinePackSaveFence,
  ): Promise<OfflinePackInspection>;
  beginDeletion(collectionId?: string): Promise<OfflinePackDeletionFence>;
  completeDeletion(fence: OfflinePackDeletionFence): Promise<boolean>;
  inspect(): Promise<OfflinePackInspection | null>;
  unlock(
    passphrase: string,
    expectedOwnerScope?: string,
  ): Promise<OfflineCollectionPack>;
  purge(): Promise<boolean>;
  purgeMismatched(ownerScope: string): Promise<boolean>;
  lock(): void;
  subscribe(listener: (event: OfflinePackEvent) => void): () => void;
}

declare global {
  var OpenScholarOfflinePack: OpenScholarOfflinePackRuntime | undefined;
}
