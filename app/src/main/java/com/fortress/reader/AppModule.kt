package com.fortress.reader

import android.content.Context
import com.fortress.reader.document.RendererRegistry
import com.fortress.reader.security.KeyWrapper
import com.fortress.reader.security.KeystoreKeyWrapper
import com.fortress.reader.security.SecureTemp
import com.fortress.reader.vault.LibraryRepository
import com.fortress.reader.vault.NoteRepository
import com.fortress.reader.vault.PhotoRepository
import com.fortress.reader.vault.SafeAuthRepository
import com.fortress.reader.vault.VaultDatabase
import com.fortress.reader.vault.VaultRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): VaultDatabase = VaultDatabase.create(ctx)

    @Provides @Singleton
    fun keyWrapper(): KeyWrapper = KeystoreKeyWrapper()

    @Provides @Singleton
    fun secureTemp(@ApplicationContext ctx: Context): SecureTemp = SecureTemp(ctx).also { it.wipeAll() }

    @Provides @Singleton
    fun vaultRepo(
        @ApplicationContext ctx: Context,
        db: VaultDatabase,
        wrapper: KeyWrapper,
        temp: SecureTemp,
    ): VaultRepository = VaultRepository(ctx, db.dao(), wrapper, temp)

    @Provides @Singleton
    fun noteRepo(db: VaultDatabase, wrapper: KeyWrapper): NoteRepository =
        NoteRepository(db.notes(), wrapper)

    @Provides @Singleton
    fun photoRepo(
        @ApplicationContext ctx: Context,
        db: VaultDatabase,
        wrapper: KeyWrapper,
        temp: SecureTemp,
    ): PhotoRepository = PhotoRepository(ctx, db.photos(), wrapper, temp)

    @Provides @Singleton
    fun safeAuth(
        db: VaultDatabase,
        vault: VaultRepository,
        notes: NoteRepository,
        photos: PhotoRepository,
    ): SafeAuthRepository = SafeAuthRepository(db.safeAuth()) {
        // Duress wipe: destroy every piece of ciphertext and metadata in the safe.
        vault.wipeAll()
        notes.wipeAll()
        photos.wipeAll()
    }

    @Provides @Singleton
    fun libraryRepo(@ApplicationContext ctx: Context): LibraryRepository = LibraryRepository(ctx)

    @Provides @Singleton
    fun rendererRegistry(): RendererRegistry = RendererRegistry.default()

    @Provides @Singleton
    fun securityStatusProvider(@ApplicationContext ctx: Context): SecurityStatusProvider =
        SecurityStatusProvider(ctx)
}
