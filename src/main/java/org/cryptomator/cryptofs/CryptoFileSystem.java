/*******************************************************************************
 * Copyright (c) 2016 Sebastian Stenzel and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the accompanying LICENSE.txt.
 *
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 *******************************************************************************/
package org.cryptomator.cryptofs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.exists;
import static org.cryptomator.cryptofs.Constants.DIR_PREFIX;
import static org.cryptomator.cryptofs.Constants.NAME_SHORTENING_THRESHOLD;
import static org.cryptomator.cryptofs.Constants.SEPARATOR;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.apache.commons.lang3.ArrayUtils;
import org.cryptomator.cryptofs.CryptoPathMapper.Directory;
import org.cryptomator.cryptolib.api.Cryptor;

@PerFileSystem
class CryptoFileSystem extends FileSystem {

	private final CryptoPath rootPath;
	private final CryptoPath emptyPath;

	private final CryptoFileSystemProvider provider;
	private final CryptoFileSystems cryptoFileSystems;
	private final Path pathToVault;
	private final Cryptor cryptor;
	private final CryptoPathMapper cryptoPathMapper;
	private final LongFileNameProvider longFileNameProvider;
	private final CryptoFileAttributeProvider fileAttributeProvider;
	private final CryptoFileAttributeViewProvider fileAttributeViewProvider;
	private final OpenCryptoFiles openCryptoFiles;
	private final CryptoFileStore fileStore;
	private final PathMatcherFactory pathMatcherFactory;
	private final CryptoPathFactory cryptoPathFactory;
	private final CryptoFileSystemStats stats;

	@Inject
	public CryptoFileSystem(@PathToVault Path pathToVault, CryptoFileSystemProperties properties, Cryptor cryptor, CryptoFileSystemProvider provider, CryptoFileSystems cryptoFileSystems, CryptoFileStore fileStore,
			OpenCryptoFiles openCryptoFiles, CryptoPathMapper cryptoPathMapper, LongFileNameProvider longFileNameProvider, CryptoFileAttributeProvider fileAttributeProvider,
			CryptoFileAttributeViewProvider fileAttributeViewProvider, PathMatcherFactory pathMatcherFactory, CryptoPathFactory cryptoPathFactory, CryptoFileSystemStats stats,
			RootDirectoryInitializer rootDirectoryInitializer) {
		this.cryptor = cryptor;
		this.provider = provider;
		this.cryptoFileSystems = cryptoFileSystems;
		this.pathToVault = pathToVault;
		this.cryptoPathMapper = cryptoPathMapper;
		this.longFileNameProvider = longFileNameProvider;
		this.fileAttributeProvider = fileAttributeProvider;
		this.fileAttributeViewProvider = fileAttributeViewProvider;
		this.openCryptoFiles = openCryptoFiles;
		this.fileStore = fileStore;
		this.pathMatcherFactory = pathMatcherFactory;
		this.cryptoPathFactory = cryptoPathFactory;
		this.stats = stats;
		this.rootPath = cryptoPathFactory.rootFor(this);
		this.emptyPath = cryptoPathFactory.emptyFor(this);

		rootDirectoryInitializer.initialize(rootPath);
	}

	/* java.nio.file.FileSystem API */

	@Override
	public FileSystemProvider provider() {
		return provider;
	}

	@Override
	public boolean isReadOnly() {
		return false;
	}

	@Override
	public String getSeparator() {
		return SEPARATOR;
	}

	@Override
	public Iterable<Path> getRootDirectories() {
		return Collections.singleton(getRootPath());
	}

	@Override
	public Iterable<FileStore> getFileStores() {
		return Collections.singleton(fileStore);
	}

	@Override
	public void close() {
		// TODO implement correct closing behavior:
		// * close all streams, channels, watch services
		// * let further access to all paths etc. fail with ClosedFileSystemException
		cryptoFileSystems.remove(this);
		cryptor.destroy();
	}

	@Override
	public boolean isOpen() {
		return cryptoFileSystems.contains(this);
	}

	@Override
	public Set<String> supportedFileAttributeViews() {
		// essentially we support posix or dos, if we need to intercept the attribute views. otherwise we support just anything
		return pathToVault.getFileSystem().supportedFileAttributeViews();
	}

	@Override
	public CryptoPath getPath(String first, String... more) {
		return cryptoPathFactory.getPath(this, first, more);
	}

	@Override
	public PathMatcher getPathMatcher(String syntaxAndPattern) {
		return pathMatcherFactory.pathMatcherFrom(syntaxAndPattern);
	}

	@Override
	public UserPrincipalLookupService getUserPrincipalLookupService() {
		throw new UnsupportedOperationException();
	}

	@Override
	public WatchService newWatchService() throws IOException {
		throw new UnsupportedOperationException();
	}

	/* methods delegated to by CryptoFileSystemProvider */

	public void setAttribute(Path cleartextPath, String attribute, Object value, LinkOption... options) {
		// TODO
	}

	public Map<String, Object> readAttributes(Path cleartextPath, String attributes, LinkOption... options) {
		// TODO
		return null;
	}

	public <A extends BasicFileAttributes> A readAttributes(Path cleartextPath, Class<A> type, LinkOption... options) throws IOException {
		Path ciphertextDirPath = cryptoPathMapper.getCiphertextDirPath(cleartextPath);
		if (Files.notExists(ciphertextDirPath) && cleartextPath.getNameCount() > 0) {
			Path ciphertextFilePath = cryptoPathMapper.getCiphertextFilePath(cleartextPath);
			return fileAttributeProvider.readAttributes(ciphertextFilePath, type);
		} else {
			return fileAttributeProvider.readAttributes(ciphertextDirPath, type);
		}
	}

	public <V extends FileAttributeView> V getFileAttributeView(Path cleartextPath, Class<V> type, LinkOption... options) {
		try {
			Path ciphertextDirPath = cryptoPathMapper.getCiphertextDirPath(cleartextPath);
			if (Files.notExists(ciphertextDirPath) && cleartextPath.getNameCount() > 0) {
				Path ciphertextFilePath = cryptoPathMapper.getCiphertextFilePath(cleartextPath);
				return fileAttributeViewProvider.getAttributeView(ciphertextFilePath, type);
			} else {
				return fileAttributeViewProvider.getAttributeView(ciphertextDirPath, type);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public void checkAccess(Path cleartextPath, AccessMode... modes) throws IOException {
		if (fileStore.supportsFileAttributeView(PosixFileAttributeView.class)) {
			Set<PosixFilePermission> permissions = readAttributes(cleartextPath, PosixFileAttributes.class).permissions();
			boolean accessGranted = true;
			for (AccessMode accessMode : modes) {
				switch (accessMode) {
				case READ:
					accessGranted &= permissions.contains(PosixFilePermission.OWNER_READ);
					break;
				case WRITE:
					accessGranted &= permissions.contains(PosixFilePermission.OWNER_WRITE);
					break;
				case EXECUTE:
					accessGranted &= permissions.contains(PosixFilePermission.OWNER_EXECUTE);
					break;
				default:
					throw new UnsupportedOperationException("AccessMode " + accessMode + " not supported.");
				}
			}
			if (!accessGranted) {
				throw new AccessDeniedException(cleartextPath.toString());
			}
		} else if (fileStore.supportsFileAttributeView(DosFileAttributeView.class)) {
			DosFileAttributes attrs = readAttributes(cleartextPath, DosFileAttributes.class);
			if (ArrayUtils.contains(modes, AccessMode.WRITE) && attrs.isReadOnly()) {
				throw new AccessDeniedException(cleartextPath.toString(), null, "read only file");
			}
		} else {
			// read attributes to check for file existence / throws IOException if file does not exist
			readAttributes(cleartextPath, BasicFileAttributes.class);
		}
	}

	public boolean isHidden(Path cleartextPath) throws IOException {
		if (fileStore.supportsFileAttributeView(DosFileAttributeView.class)) {
			DosFileAttributeView view = this.getFileAttributeView(cleartextPath, DosFileAttributeView.class);
			return view.readAttributes().isHidden();
		} else {
			return false;
		}
	}

	public void createDirectory(Path cleartextDir, FileAttribute<?>... attrs) throws IOException {
		Path cleartextParentDir = cleartextDir.getParent();
		if (cleartextParentDir == null) {
			return;
		}
		Directory ciphertextParentDir = cryptoPathMapper.getCiphertextDir(cleartextParentDir);
		if (!exists(ciphertextParentDir.path)) {
			throw new NoSuchFileException(cleartextParentDir.toString());
		}
		String cleartextDirName = cleartextDir.getFileName().toString();
		String ciphertextName = cryptor.fileNameCryptor().encryptFilename(cleartextDirName, ciphertextParentDir.dirId.getBytes(UTF_8));
		if (exists(ciphertextParentDir.path.resolve(ciphertextName))) {
			throw new FileAlreadyExistsException(cleartextDir.toString());
		}
		String ciphertextDirName = DIR_PREFIX + ciphertextName;
		if (ciphertextDirName.length() >= NAME_SHORTENING_THRESHOLD) {
			ciphertextDirName = longFileNameProvider.deflate(ciphertextDirName);
		}
		Path dirFile = ciphertextParentDir.path.resolve(ciphertextDirName);
		Directory ciphertextDir = cryptoPathMapper.getCiphertextDir(cleartextDir);
		try (FileChannel channel = FileChannel.open(dirFile, EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attrs)) {
			channel.write(ByteBuffer.wrap(ciphertextDir.dirId.getBytes(UTF_8)));
		}
		boolean success = false;
		try {
			Files.createDirectories(ciphertextDir.path);
			success = true;
		} finally {
			if (!success) {
				Files.delete(dirFile);
			}
		}
	}

	public DirectoryStream<Path> newDirectoryStream(Path cleartextDir, Filter<? super Path> filter) throws IOException {
		Directory ciphertextDir = cryptoPathMapper.getCiphertextDir(cleartextDir);
		return new CryptoDirectoryStream(ciphertextDir, cleartextDir, cryptor.fileNameCryptor(), longFileNameProvider, filter);
	}

	public FileChannel newFileChannel(Path cleartextPath, Set<? extends OpenOption> optionsSet, FileAttribute<?>... attrs) throws IOException {
		EffectiveOpenOptions options = EffectiveOpenOptions.from(optionsSet);
		Path ciphertextPath = cryptoPathMapper.getCiphertextFilePath(cleartextPath);
		return openCryptoFiles.get(ciphertextPath, options).newFileChannel(options);
	}

	public void delete(Path cleartextPath) {
		// TODO
	}

	public void copy(Path cleartextSource, Path cleartextTarget, CopyOption... options) {
		// TODO
	}

	public void move(Path cleartextSource, Path cleartextTarget, CopyOption... options) {
		// TODO
	}

	public CryptoFileStore getFileStore() {
		return fileStore;
	}

	/* internal methods */

	public CryptoPath getRootPath() {
		return rootPath;
	}

	public CryptoPath getEmptyPath() {
		return emptyPath;
	}

	public Path getPathToVault() {
		return pathToVault;
	}

	public CryptoFileSystemStats getStats() {
		return stats;
	}

}
