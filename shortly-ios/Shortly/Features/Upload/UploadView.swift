import AVKit
import PhotosUI
import SwiftUI

struct UploadView: View {
    @EnvironmentObject private var appModel: AppModel
    @State private var selection: PhotosPickerItem?
    @State private var selectedMovie: SelectedMovie?
    @State private var title = ""
    @State private var videoDescription = ""
    @State private var previewPlayer: AVPlayer?
    @State private var isLoadingSelection = false
    @State private var localError: String?
    let onUploaded: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    Text("Upload a reel")
                        .font(.largeTitle.bold())

                    picker

                    if isLoadingSelection {
                        ProgressView("Preparing video")
                            .frame(maxWidth: .infinity, minHeight: 220)
                    } else if let previewPlayer {
                        VideoPlayer(player: previewPlayer)
                            .aspectRatio(9.0 / 16.0, contentMode: .fit)
                            .frame(maxHeight: 420)
                            .clipShape(RoundedRectangle(cornerRadius: 20))
                            .onDisappear {
                                previewPlayer.pause()
                            }
                    } else {
                        emptyPreview
                    }

                    VStack(alignment: .leading, spacing: 14) {
                        TextField("Title", text: $title)
                            .textInputAutocapitalization(.sentences)
                            .padding(15)
                            .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
                            .accessibilityIdentifier("upload.title")

                        TextField("Description", text: $videoDescription, axis: .vertical)
                            .lineLimit(3...6)
                            .padding(15)
                            .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
                            .accessibilityIdentifier("upload.description")
                    }

                    if appModel.isUploading {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(appModel.uploadStage)
                                Spacer()
                                Text(appModel.uploadProgress, format: .percent.precision(.fractionLength(0)))
                            }
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            ProgressView(value: appModel.uploadProgress)
                                .tint(.pink)
                        }
                    }

                    Button {
                        submit()
                    } label: {
                        HStack {
                            if appModel.isUploading {
                                ProgressView()
                                    .tint(.white)
                            }
                            Text(appModel.isUploading ? "Uploading" : "Publish video")
                                .fontWeight(.bold)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 15)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.roundedRectangle(radius: 14))
                    .disabled(!canUpload)
                    .accessibilityIdentifier("upload.submit")
                }
                .padding(20)
            }
            .background(Color.black)
            .navigationTitle("Create")
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: selection) {
                Task {
                    await loadSelection()
                }
            }
            .alert("Upload unavailable", isPresented: errorBinding) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(localError ?? "The selected video could not be read.")
            }
        }
    }

    private var picker: some View {
        PhotosPicker(selection: $selection, matching: .videos) {
            Label(selectedMovie == nil ? "Choose a video" : "Choose another video", systemImage: "photo.on.rectangle")
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
        }
        .buttonStyle(.bordered)
        .disabled(appModel.isUploading)
        .accessibilityIdentifier("upload.picker")
    }

    private var emptyPreview: some View {
        RoundedRectangle(cornerRadius: 20)
            .fill(.white.opacity(0.05))
            .aspectRatio(9.0 / 16.0, contentMode: .fit)
            .overlay {
                VStack(spacing: 12) {
                    Image(systemName: "video.badge.plus")
                        .font(.system(size: 42))
                        .foregroundStyle(.pink)
                    Text("Choose a video from your library")
                        .foregroundStyle(.secondary)
                }
            }
    }

    private var canUpload: Bool {
        selectedMovie != nil && !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !appModel.isUploading
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { localError != nil },
            set: { if !$0 { localError = nil } }
        )
    }

    private func loadSelection() async {
        isLoadingSelection = true
        defer {
            isLoadingSelection = false
        }

        do {
            previewPlayer?.pause()
            removeTemporaryMovie()

            guard let selection else {
                selectedMovie = nil
                previewPlayer = nil
                return
            }

            let movie = try await selection.loadTransferable(type: SelectedMovie.self)
            selectedMovie = movie
            previewPlayer = movie.map { AVPlayer(url: $0.url) }

            if title.isEmpty, let movie {
                title = movie.url.deletingPathExtension().lastPathComponent
            }
        } catch {
            selectedMovie = nil
            previewPlayer = nil
            localError = error.localizedDescription
        }
    }

    private func submit() {
        guard let movie = selectedMovie else {
            return
        }

        Task {
            do {
                _ = try await appModel.uploadVideo(
                    fileURL: movie.url,
                    contentType: movie.contentType,
                    title: title,
                    description: videoDescription
                )
                removeTemporaryMovie()
                selectedMovie = nil
                previewPlayer?.pause()
                previewPlayer = nil
                selection = nil
                title = ""
                videoDescription = ""
                onUploaded()
            } catch {}
        }
    }

    private func removeTemporaryMovie() {
        guard let selectedMovie else {
            return
        }

        try? FileManager.default.removeItem(at: selectedMovie.url)
    }
}
