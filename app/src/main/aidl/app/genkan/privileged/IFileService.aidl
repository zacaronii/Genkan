// File access that runs with Shizuku's shell identity, for saves in other apps' Android/data.
package app.genkan.privileged;

interface IFileService {
    /** Shizuku's reserved transaction for tearing the service down. */
    void destroy() = 16777114;

    /** Every file under dir, as "relative path\tsize\tlast modified ms"; empty when dir is missing. */
    String[] list(String dir) = 1;

    /** Copies a file or a folder (its contents merged into to), overwriting; false on any failure. */
    boolean copy(String from, String to) = 2;

    boolean deleteRecursively(String path) = 3;
}
