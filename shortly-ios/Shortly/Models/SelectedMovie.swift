import CoreTransferable
import Foundation
import UniformTypeIdentifiers

struct SelectedMovie: Transferable {
    let url: URL

    var contentType: String {
        UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "video/mp4"
    }

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let fileExtension = received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(fileExtension)

            try FileManager.default.copyItem(at: received.file, to: destination)
            return SelectedMovie(url: destination)
        }
    }
}
