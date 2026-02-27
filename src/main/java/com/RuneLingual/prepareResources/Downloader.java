package com.RuneLingual.prepareResources;

import com.RuneLingual.LangCodeSelectableList;
import com.RuneLingual.RuneLingualPlugin;
import com.RuneLingual.SQL.SqlActions;
import com.RuneLingual.commonFunctions.FileActions;
import com.RuneLingual.commonFunctions.FileNameAndPath;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


@Slf4j
public class Downloader {//downloads translations and japanese char images to external file
    @Getter
    public static final File localBaseFolder = FileNameAndPath.getLocalBaseFolder();
    @Inject
    private RuneLingualPlugin plugin;
    @Getter
    private File localLangFolder;
    private String GITHUB_BASE_URL;
    @Setter
    @Getter
    private String langCode;
    @Inject
    private DataFormater dataFormater;


    @Inject
    public Downloader(RuneLingualPlugin plugin) {
        this.plugin = plugin;
    }

    // returns if char image changed
    public void initDownloader() {
        LangCodeSelectableList langCodeSelectableList = plugin.getConfig().getSelectedLanguage();
        if (langCodeSelectableList == LangCodeSelectableList.ENGLISH) {
            return;
        }
        final List<String> extensions_to_download = new ArrayList<>(); // will download all files with these extensions
        // Only download zip from hash loop if this language has its own char images
        // (variants like ja_nk reuse the base language sprites, handled separately below)
        if (langCodeSelectableList.needsCharImages()
                && langCodeSelectableList.getCharImageLangCode().equals(langCode)){
            extensions_to_download.add("zip");
        }
        if (langCodeSelectableList.hasLocalTranscript()){
            extensions_to_download.add("png");//for swapping sprites
            extensions_to_download.add("tsv");
        }
        if (extensions_to_download.isEmpty()) {
            return;
        }
        List<String> fileNames = new ArrayList<>();
        // Only include char zip in hash-driven downloads if this language has its own sprites
        if (langCodeSelectableList.getCharImageLangCode().equals(langCode)) {
            fileNames.add("char_" + langCode + ".zip");
        }
        fileNames.add("latin2foreign_" + langCode + ".txt");
        fileNames.add("foreign2foreign_" + langCode + ".txt");
        final List<String> file_name_to_download = List.copyOf(fileNames);
        localLangFolder = new File(FileNameAndPath.getLocalLangFolder(langCodeSelectableList));

        createDir(localLangFolder.getPath());
        String LOCAL_HASH_NAME = "hashListLocal_" + langCode + ".txt";
        String remote_sub_folder = "public"; //todo: this value is "draft" if reading from draft folder, "public" if reading from the public folder
        GITHUB_BASE_URL = "https://raw.githubusercontent.com/YS-jack/Runelingual-Transcripts/original-main/" +
                remote_sub_folder + "/" + langCode + "/";

        // if the plugin is configured to use custom data, use that instead of the default GitHub URL
        // example of customDataUrl: https://raw.githubusercontent.com/YS-jack/Runelingual-Transcripts/original-main/draft/
        String customDataUrl = plugin.getConfig().getCustomDataUrl();
        if (!customDataUrl.endsWith("/")) {
            customDataUrl = customDataUrl + "/";
        }
        customDataUrl = customDataUrl + langCode + "/";
        if(plugin.getConfig().useCustomData() && isURLReachable(customDataUrl + "hashList_" + langCode + ".txt")) {
            GITHUB_BASE_URL = customDataUrl;
            //log.info("Using custom data URL: " + GITHUB_BASE_URL);
        } else {
            log.error("Custom data URL is not reachable or not configured, using default GitHub URL: " + GITHUB_BASE_URL);
        }

        String REMOTE_HASH_FILE = GITHUB_BASE_URL + "hashList_" + langCode + ".txt";

        try {
            Path dirPath = Paths.get(localBaseFolder.getPath());
            if (!Files.exists(dirPath)) {
                try {
                    // Attempt to create the directory
                    Files.createDirectories(dirPath);
                } catch (IOException e) {
                    log.error("Error creating directory", e);
                }
            }

            Map<String, String> localHashes = readHashFile(Paths.get(localLangFolder.getPath(), LOCAL_HASH_NAME));
            Map<String, String> remoteHashes = readHashFile(new URL(REMOTE_HASH_FILE));

            boolean dataChanged = false;
            boolean transcriptChanged = false;
            List<String> remoteTsvFileNames = new ArrayList<>(); // list of tsv files to include in the sql database


            for (Map.Entry<String, String> entry : remoteHashes.entrySet()) {
                String localHash = localHashes.get(entry.getKey());
                String remoteHash = entry.getValue();
                String remote_full_path = entry.getKey();
                Connection conn = plugin.getH2Manager().getConn(plugin.getTargetLanguage());

                if ((localHash == null || !localHash.equals(remoteHash) || SqlActions.noTableExistsOrIsEmpty(conn)) // if the file is not in the local hash file OR the hash value is different OR the table TRANSCRIPT exists but empty
                        && (fileExtensionIncludedIn(remote_full_path, extensions_to_download) // and if the file extension is in the list of extensions to download
                        || same_file_included(remote_full_path, file_name_to_download))) { // or if the file name is in the list of file names to download

                    dataChanged = true;
                    downloadAndUpdateFile(remote_full_path);
                    if (fileExtensionIncludedIn(remote_full_path, List.of("zip"))) { // if its a zip file, unzip it
                        updateCharDir(Paths.get(localLangFolder.getPath(), "char_" + langCode + ".zip")); // currently only supports char images, which should suffice
                    } else {
                        transcriptChanged = true; // if the file is not a zip file, then one of the transcripts has changed
                    }
                }

                if (fileExtensionIncludedIn(remote_full_path, List.of("tsv"))) {
                    remoteTsvFileNames.add(remote_full_path);
                }
            }
            String[] tsvFileNames = remoteTsvFileNames.toArray(new String[0]);
            this.plugin.setTsvFileNames(tsvFileNames);

            if (dataChanged) {
                // Overwrite local hash file with the updated remote hash file
                Files.copy(new URL(REMOTE_HASH_FILE).openStream(), Paths.get(localLangFolder.getPath(), LOCAL_HASH_NAME), StandardCopyOption.REPLACE_EXISTING);
                if (transcriptChanged) {
                    dataFormater.updateSqlFromTsv(localLangFolder.getPath(), tsvFileNames);
                }
            } else {
                //log.info("All files are up to date.");
            }

        } catch (IOException e) {
            log.error("An error occurred: {}", e.getMessage(), e);
        }

        // Ensure char images are available.
        // If this language has its own char zip (e.g., char_ja_nk.zip), download and use it.
        // Otherwise fall back to the base language's sprites (e.g., ja's char_ja/).
        if (langCodeSelectableList.needsCharImages()) {
            String charLangCode = langCodeSelectableList.getCharImageLangCode();
            boolean hasOwnChars = false;

            // For variants, always try to get their own char zip if not already downloaded
            if (!charLangCode.equals(langCode)) {
                File ownCharDir = new File(localLangFolder, "char_" + langCode);
                if (ownCharDir.isDirectory()) {
                    hasOwnChars = true;
                } else {
                    hasOwnChars = downloadCharZipToFolder(langCode, localLangFolder);
                }
            }

            // If no variant-specific chars, ensure the base language sprites exist
            if (!hasOwnChars) {
                File charBaseFolder = new File(localBaseFolder, charLangCode);
                File charDir = new File(charBaseFolder, "char_" + charLangCode);
                if (!charDir.isDirectory()) {
                    createDir(charBaseFolder.getPath());
                    downloadCharZipToFolder(charLangCode, charBaseFolder);
                }
            }
        }
    }

    public void createDir(String path) {
        Path dirPath = Paths.get(path);
        if (!Files.exists(dirPath)) {
            try {
                // Attempt to create the directory
                Files.createDirectories(dirPath);
            } catch (IOException e) {
                log.error("Error creating directory", e);
            }
        }
    }

    private Boolean fileExtensionIncludedIn(String file_full_path, List<String> extensions) {
        String extension = get_file_extension(file_full_path);
        return extensions.contains(extension);
    }

    private String get_file_extension(String file_full_path) {
        String[] parts = file_full_path.split("\\.");
        return parts[parts.length - 1];
    }

    private Boolean same_file_included(String file_full_path, List<String> file_names) {
        Path fullPath = Paths.get(file_full_path);
        Path fileName = fullPath.getFileName();
        String fileNameString = fileName.toString();
        return file_names.contains(fileNameString);
    }

    private Map<String, String> readHashFile(Path filePath) throws IOException {
        Map<String, String> hashes = new HashMap<>();
        if (!Files.exists(filePath)) {
            Files.createDirectories(filePath.getParent());
            Files.createFile(filePath);
        }
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length == 2) {
                    hashes.put(parts[0], parts[1]);
                }
            }
        }
        return hashes;
    }

    private Map<String, String> readHashFile(URL fileUrl) throws IOException {
        Map<String, String> hashes = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(fileUrl.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length == 2) {
                    hashes.put(parts[0], parts[1]);
                }
            }
        }
        return hashes;
    }

    private void downloadAndUpdateFile(String remoteFullPath) throws IOException {
        // filePath example: "draft\ja\actions_ja.xliff" this is the location of files relative to the GitHub repo root of RuneLite-Transcripts
        URL fileUrl = new URL(GITHUB_BASE_URL + remoteFullPath.replace("\\", "/"));
        Path localPath = Paths.get(localLangFolder.getPath(), remoteFullPath.replace("draft\\", ""));
        //log.info("updating file " + localPath);


        // Check if the language directory exists, if not, create it
        if (Files.notExists(localPath.getParent())) {
            Files.createDirectories(localPath.getParent());
        }

        Files.copy(fileUrl.openStream(), localPath, StandardCopyOption.REPLACE_EXISTING);
    }

    public void updateCharDir(Path localPath) throws IOException {
        URL fileUrl3 = new URL(GITHUB_BASE_URL + "char_" + langCode + ".zip");
        Files.copy(fileUrl3.openStream(), localPath, StandardCopyOption.REPLACE_EXISTING);
        unzipCharDir(String.valueOf(localPath), localLangFolder.getPath(), langCode);
        Files.delete(localPath);
    }

    public void unzipCharDir(String zipFilePath, String destDir, String charLangCode) {
        File charDir = new File(destDir + File.separator + "char_" + charLangCode);
        if (charDir.isFile()) {
            charDir.delete(); // clean up if a previous bad unzip left a file instead of a directory
        }
        FileActions.deleteFolder(charDir.getPath());
        //log.info("unzipping " + zipFilePath + " to " + destDir);
        File dir = new File(destDir);
        // create output directory if it doesn't exist
        if (!dir.exists()) dir.mkdirs();
        FileInputStream fis;
        //buffer for read and write data to file
        byte[] buffer = new byte[1024];
        try {
            fis = new FileInputStream(zipFilePath);
            ZipInputStream zis = new ZipInputStream(fis);
            ZipEntry ze = zis.getNextEntry();
            while (ze != null) {
                String fileName = ze.getName();
                File newFile = new File(destDir + File.separator + fileName);
                if (ze.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    //create directories for sub directories in zip
                    new File(newFile.getParent()).mkdirs();
                    FileOutputStream fos = new FileOutputStream(newFile);
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                }
                //close this ZipEntry
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
            //close last ZipEntry
            zis.closeEntry();
            zis.close();
            fis.close();
        } catch (IOException e) {
            log.error("Error unzipping file", e);
        }
    }

    /**
     * Download char_{charLangCode}.zip and extract it into destFolder.
     * Returns true if successful, false if the zip doesn't exist or download failed.
     */
    private boolean downloadCharZipToFolder(String charLangCode, File destFolder) {
        try {
            // Derive URL from GITHUB_BASE_URL, replacing current langCode with charLangCode
            String baseUrl = GITHUB_BASE_URL.substring(0, GITHUB_BASE_URL.length() - langCode.length() - 1)
                    + charLangCode + "/";
            String charZipUrl = baseUrl + "char_" + charLangCode + ".zip";
            if (!isURLReachable(charZipUrl, 1500)) {
                log.info("char_{}.zip not available at {}", charLangCode, charZipUrl);
                return false;
            }

            Path localZipPath = Paths.get(destFolder.getPath(), "char_" + charLangCode + ".zip");
            log.info("Downloading char images from {}", charZipUrl);
            Files.copy(new URL(charZipUrl).openStream(), localZipPath, StandardCopyOption.REPLACE_EXISTING);
            unzipCharDir(String.valueOf(localZipPath), destFolder.getPath(), charLangCode);
            Files.delete(localZipPath);
            log.info("Successfully downloaded and extracted char images for {} to {}", charLangCode, destFolder);
            return true;
        } catch (IOException e) {
            log.error("Error downloading char images for {}: {}", charLangCode, e.getMessage(), e);
            return false;
        }
    }

    public static boolean isURLReachable(String urlString) {
        return isURLReachable(urlString, 5000);
    }

    public static boolean isURLReachable(String urlString, int timeoutMs) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            int responseCode = connection.getResponseCode();
            return (200 <= responseCode && responseCode < 400);
        } catch (Exception e) {
            return false;
        }
    }
}
