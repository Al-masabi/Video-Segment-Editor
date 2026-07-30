package com.banoon.vse.infrastructure.di

import com.banoon.vse.domain.port.FfmpegPort
import com.banoon.vse.domain.port.MediaExportPort
import com.banoon.vse.domain.port.MediaProbePort
import com.banoon.vse.domain.port.VideoFileResolverPort
import com.banoon.vse.domain.usecase.SmartRenderingPlanner
import com.banoon.vse.infrastructure.ffmpeg.FFmpegKitAdapter
import com.banoon.vse.infrastructure.ffmpeg.FFprobeAdapter
import com.banoon.vse.infrastructure.storage.MediaStoreExporter
import com.banoon.vse.infrastructure.storage.SafVideoResolver
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PortsModule {

    @Binds
    @Singleton
    abstract fun bindMediaProbePort(impl: FFprobeAdapter): MediaProbePort

    @Binds
    @Singleton
    abstract fun bindFfmpegPort(impl: FFmpegKitAdapter): FfmpegPort

    @Binds
    @Singleton
    abstract fun bindVideoFileResolverPort(impl: SafVideoResolver): VideoFileResolverPort

    @Binds
    @Singleton
    abstract fun bindMediaExportPort(impl: MediaStoreExporter): MediaExportPort
}

@Module
@InstallIn(SingletonComponent::class)
object DomainServicesModule {

    @Provides
    @Singleton
    fun provideSmartRenderingPlanner(): SmartRenderingPlanner = SmartRenderingPlanner()
}
