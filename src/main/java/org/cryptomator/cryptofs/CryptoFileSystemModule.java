/*******************************************************************************
 * Copyright (c) 2017 Skymatic UG (haftungsbeschränkt).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the accompanying LICENSE file.
 *******************************************************************************/
package org.cryptomator.cryptofs;

import dagger.Module;
import dagger.Provides;
import org.cryptomator.cryptofs.attr.AttributeComponent;
import org.cryptomator.cryptofs.attr.AttributeViewComponent;
import org.cryptomator.cryptofs.common.Constants;
import org.cryptomator.cryptofs.common.MasterkeyBackupFileHasher;
import org.cryptomator.cryptofs.dir.DirectoryStreamComponent;
import org.cryptomator.cryptofs.fh.OpenCryptoFileComponent;
import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.CryptorProvider;
import org.cryptomator.cryptolib.api.KeyFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

@Module(subcomponents = {AttributeComponent.class, AttributeViewComponent.class, OpenCryptoFileComponent.class, DirectoryStreamComponent.class})
class CryptoFileSystemModule {

	private static final Logger LOG = LoggerFactory.getLogger(CryptoFileSystemModule.class);

	@Provides
	@CryptoFileSystemScoped
	public Cryptor provideCryptor(CryptorProvider cryptorProvider, @PathToVault Path pathToVault, CryptoFileSystemProperties properties, ReadonlyFlag readonlyFlag) {
		try {
			Path masterKeyPath = pathToVault.resolve(properties.masterkeyFilename());
			assert Files.exists(masterKeyPath); // since 1.3.0 a file system can only be created for existing vaults. initialization is done before.
			byte[] keyFileContents = Files.readAllBytes(masterKeyPath);
			Path backupKeyPath = pathToVault.resolve(properties.masterkeyFilename() + MasterkeyBackupFileHasher.generateFileIdSuffix(keyFileContents) + Constants.MASTERKEY_BACKUP_SUFFIX);
			Cryptor cryptor = cryptorProvider.createFromKeyFile(KeyFile.parse(keyFileContents), properties.passphrase(), properties.pepper(), Constants.VAULT_VERSION);
			backupMasterKeyAndOrValidateBackup(keyFileContents, masterKeyPath, backupKeyPath, readonlyFlag);
			return cryptor;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void backupMasterKeyAndOrValidateBackup(byte[] keyFileContents, Path masterKeyPath, Path backupKeyPath, ReadonlyFlag readonlyFlag) throws IOException {
		if (!readonlyFlag.isSet()) {
			try {
				Files.copy(masterKeyPath, backupKeyPath, REPLACE_EXISTING);
			} catch (AccessDeniedException e) {
				LOG.info("Storage device does not allow writing backup file. Comparing masterkey with backup directly.");
				try {
					byte[] backupFileContents = Files.readAllBytes(backupKeyPath);
					if (!Arrays.equals(keyFileContents, backupFileContents)) {
						throw new IllegalStateException("Content of masterkey backup does not match working masterkey, but cannot be replaced: Storage is read-only.");
					}
				} catch (NoSuchFileException e2) {
					throw new IllegalStateException("Storage is read-only, but backup file for a bitwise comparsion to masterkey does not exist.");
				}
			}
		}
	}

	@Provides
	@CryptoFileSystemScoped
	public Optional<FileStore> provideNativeFileStore(@PathToVault Path pathToVault) {
		try {
			return Optional.of(Files.getFileStore(pathToVault));
		} catch (IOException e) {
			LOG.warn("Failed to get file store for " + pathToVault, e);
			return Optional.empty();
		}
	}
}
