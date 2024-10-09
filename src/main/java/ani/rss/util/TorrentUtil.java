package ani.rss.util;

import ani.rss.download.BaseDownload;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.enums.MessageEnum;
import ani.rss.enums.StringEnum;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.*;
import cn.hutool.extra.pinyin.PinyinUtil;
import cn.hutool.json.JSONUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class TorrentUtil {

    @Setter
    private static BaseDownload baseDownload;

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();


    /**
     * 下载动漫
     *
     * @param ani
     */
    public static synchronized void downloadAni(Ani ani) {
        Config config = ConfigUtil.CONFIG;
        Boolean autoDisabled = config.getAutoDisabled();
        Integer downloadCount = config.getDownloadCount();

        String title = ani.getTitle();
        Integer season = ani.getSeason();
        Boolean downloadNew = ani.getDownloadNew();

        List<TorrentsInfo> torrentsInfos = getTorrentsInfos();

        Set<String> downloadNameList = torrentsInfos.stream()
                .map(TorrentsInfo::getName)
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        Set<String> hashList = torrentsInfos
                .stream().map(TorrentsInfo::getHash)
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        int currentDownloadCount = 0;
        List<Item> items = AniUtil.getItems(ani);

        ItemsUtil.omit(ani, items);
        log.debug("{} 共 {} 个", title, items.size());

        if (downloadNew && !items.isEmpty()) {
            log.debug("{} 已开启只下载最新集", title);
            items = List.of(items.get(items.size() - 1));
        }

        long count = getTorrentsInfos()
                .stream()
                .filter(it -> !EnumUtil.equalsIgnoreCase(it.getState(), TorrentsInfo.State.pausedUP.name()))
                .count();

        for (Item item : items) {
            log.debug(JSONUtil.formatJsonStr(GSON.toJson(item)));
            String reName = item.getReName();
            File torrent = getTorrent(ani, item);
            Boolean master = item.getMaster();
            String hash = FileUtil.mainName(torrent)
                    .trim().toLowerCase();

            // 已经下载过
            if (hashList.contains(hash) || downloadNameList.contains(reName)) {
                log.debug("已有下载任务 {}", reName);
                if (master && !reName.endsWith(".5")) {
                    currentDownloadCount++;
                }
                continue;
            }

            // 已经下载过
            if (torrent.exists()) {
                log.debug("种子记录已存在 {}", reName);
                if (master && !reName.endsWith(".5")) {
                    currentDownloadCount++;
                }
                continue;
            }

            // 未开启rename不进行检测
            if (itemDownloaded(ani, item, true)) {
                log.debug("本地文件已存在 {}", reName);
                if (master && !reName.endsWith(".5")) {
                    currentDownloadCount++;
                }
                continue;
            }

            // 同时下载数量限制
            if (downloadCount > 0) {
                if (count >= downloadCount) {
                    log.debug("达到同时下载数量限制 {}", downloadCount);
                    continue;
                }
            }

            log.info("添加下载 {}", reName);
            File saveTorrent = saveTorrent(ani, item);
            List<File> downloadPathList = getDownloadPath(ani);

            deleteBackRss(ani, item);

            String savePath = downloadPathList
                    .get(0)
                    .toString();
            download(ani, item, savePath, saveTorrent);
            if (master && !reName.endsWith(".5")) {
                currentDownloadCount++;
            }
            count++;
        }

        long size = items.stream().filter(it -> !it.getReName().endsWith(".5")).count();
        if (size > 0 && ani.getCurrentEpisodeNumber() != size) {
            ani.setCurrentEpisodeNumber((int) size);
            AniUtil.sync();
        }

        if (!autoDisabled) {
            return;
        }
        Integer totalEpisodeNumber = ani.getTotalEpisodeNumber();
        if (totalEpisodeNumber < 1) {
            return;
        }
        if (currentDownloadCount >= totalEpisodeNumber) {
            ani.setEnable(false);
            log.info("{} 第 {} 季 共 {} 集 已全部下载完成, 自动停止订阅", title, season, totalEpisodeNumber);
            AniUtil.sync();
        }
    }

    /**
     * 删除备用rss
     *
     * @param ani
     * @param item
     */
    public static void deleteBackRss(Ani ani, Item item) {
        Config config = ConfigUtil.CONFIG;
        Boolean backRss = config.getBackRss();
        Boolean delete = config.getDelete();
        String reName = item.getReName();

        if (!delete) {
            return;
        }

        if (!backRss) {
            return;
        }
        if (!ReUtil.contains(StringEnum.SEASON_REG, reName)) {
            return;
        }
        reName = ReUtil.get(StringEnum.SEASON_REG, reName, 0);

        List<File> downloadPathList = getDownloadPath(ani);

        List<File> files = downloadPathList.stream()
                .filter(File::exists)
                .filter(File::isDirectory)
                .flatMap(downloadPath -> Stream.of(ObjectUtil.defaultIfNull(downloadPath.listFiles(), new File[]{})))
                .collect(Collectors.toList());
        for (File file : files) {
            String fileMainName = FileUtil.mainName(file);
            if (StrUtil.isBlank(fileMainName)) {
                continue;
            }
            if (!ReUtil.contains(StringEnum.SEASON_REG, fileMainName)) {
                continue;
            }
            fileMainName = ReUtil.get(StringEnum.SEASON_REG, fileMainName, 0);
            if (!fileMainName.equals(reName)) {
                continue;
            }
            boolean isDel = false;
            // 文件在删除前先判断其格式
            if (file.isFile()) {
                String extName = FileUtil.extName(file);
                // 没有后缀 跳过
                if (StrUtil.isBlank(extName)) {
                    continue;
                }
                for (String en : BaseDownload.videoFormat) {
                    // 后缀匹配不上 跳过
                    if (!extName.equalsIgnoreCase(en)) {
                        continue;
                    }
                    isDel = true;
                    break;
                }
            }
            if (file.isDirectory()) {
                isDel = true;
            }
            if (isDel) {
                FileUtil.del(file);
                log.info("已开启备用RSS, 自动删除 {}", file.getAbsolutePath());
            }
        }
    }

    public static File getTorrentDir(Ani ani) {
        String title = ani.getTitle();
        Boolean ova = ani.getOva();
        Integer season = ani.getSeason();

        File configDir = ConfigUtil.getConfigDir();

        String pinyin = PinyinUtil.getPinyin(title);
        String s = pinyin.toUpperCase().substring(0, 1);
        if (ReUtil.isMatch("^\\d$", s)) {
            s = "0";
        } else if (!ReUtil.isMatch("^[a-zA-Z]$", s)) {
            s = "#";
        }

        File torrents = new File(StrFormatter.format("{}/torrents/{}/Season {}", configDir, title, season));
        if (!torrents.exists()) {
            torrents = new File(StrFormatter.format("{}/torrents/{}/{}/Season {}", configDir, s, title, season));
        }
        if (ova) {
            torrents = new File(StrFormatter.format("{}/torrents/{}", configDir, title));
            if (!torrents.exists()) {
                torrents = new File(StrFormatter.format("{}/torrents/{}/{}", configDir, s, title));
            }
        }
        FileUtil.mkdir(torrents);
        return torrents;
    }

    public static File getTorrent(Ani ani, Item item) {
        String infoHash = item.getInfoHash();
        File torrents = getTorrentDir(ani);
        String type = ani.getType();
        if ("dmhy".equals(type)) {
            return new File(torrents + "/" + infoHash + ".txt");
        }
        return new File(torrents + "/" + infoHash + ".torrent");
    }

    /**
     * 下载种子文件
     *
     * @param item
     */
    public static File saveTorrent(Ani ani, Item item) {
        String torrent = item.getTorrent();
        String reName = item.getReName();

        log.info("下载种子 {}", reName);
        File saveTorrentFile = getTorrent(ani, item);
        if (saveTorrentFile.exists()) {
            return saveTorrentFile;
        }

        try {
            String type = ani.getType();
            if ("dmhy".equals(type)) {
                FileUtil.writeUtf8String(torrent, saveTorrentFile);
                return saveTorrentFile;
            }

            return HttpReq.get(torrent, true)
                    .thenFunction(res -> {
                        FileUtil.writeFromStream(res.bodyStream(), saveTorrentFile, true);
                        return saveTorrentFile;
                    });
        } catch (Exception e) {
            String message = ExceptionUtil.getMessage(e);
            log.error("下载种子时出现问题 {}", message);
            log.error(message, e);
        }
        return saveTorrentFile;
    }

    /**
     * 判断是否已经下载过
     *
     * @param ani
     * @param item
     * @param downloadList
     * @return
     */
    public static Boolean itemDownloaded(Ani ani, Item item, Boolean downloadList) {
        Config config = ConfigUtil.CONFIG;
        Boolean rename = config.getRename();
        if (!rename) {
            return false;
        }

        String downloadPath = config.getDownloadPath();

        if (StrUtil.isBlank(downloadPath)) {
            return false;
        }

        Boolean fileExist = config.getFileExist();
        if (!fileExist) {
            return false;
        }

        Integer season = ani.getSeason();
        Boolean ova = ani.getOva();
        String reName = item.getReName();
        Double episode = item.getEpisode();

        if (downloadList) {
            List<TorrentsInfo> torrentsInfos = getTorrentsInfos();
            for (TorrentsInfo torrentsInfo : torrentsInfos) {
                String name = torrentsInfo.getName();
                if (name.equalsIgnoreCase(reName)) {
                    log.info("已存在下载任务 {}", reName);
                    saveTorrent(ani, item);
                    return true;
                }
            }
        }

        List<File> files = getDownloadPath(ani)
                .stream()
                .flatMap(file -> {
                    if (ova) {
                        return FileUtil.loopFiles(file).stream();
                    }
                    return Stream.of(ObjectUtil.defaultIfNull(file.listFiles(), new File[]{}));
                })
                .collect(Collectors.toList());

        if (files.stream()
                .filter(file -> {
                    if (file.isFile()) {
                        String extName = FileUtil.extName(file);
                        if (StrUtil.isBlank(extName)) {
                            return false;
                        }
                        return BaseDownload.videoFormat.contains(extName);
                    }
                    return true;
                })
                .anyMatch(file -> {
                    if (ova) {
                        return true;
                    }

                    String mainName = FileUtil.mainName(file);
                    if (StrUtil.isBlank(mainName)) {
                        return false;
                    }
                    mainName = mainName.trim().toUpperCase();
                    if (!ReUtil.contains(StringEnum.SEASON_REG, mainName)) {
                        return false;
                    }

                    String seasonStr = ReUtil.get(StringEnum.SEASON_REG, mainName, 1);

                    String episodeStr = ReUtil.get(StringEnum.SEASON_REG, mainName, 2);

                    if (StrUtil.isBlank(seasonStr) || StrUtil.isBlank(episodeStr)) {
                        return false;
                    }
                    return season == Integer.parseInt(seasonStr) && episode == Double.parseDouble(episodeStr);
                })) {
            // 保存 torrent 下次只校验 torrent 是否存在 ， 可以将config设置到固态硬盘，防止一直唤醒机械硬盘
            saveTorrent(ani, item);
            log.info("本地已存在 {}", reName);
            return true;
        }

        return false;
    }

    /**
     * 获取下载位置
     *
     * @param ani
     * @return
     */
    public static List<File> getDownloadPath(Ani ani) {
        Boolean customDownloadPath = ani.getCustomDownloadPath();
        String aniDownloadPath = ani.getDownloadPath();

        if (customDownloadPath && StrUtil.isNotBlank(aniDownloadPath)) {
            return List.of(new File(aniDownloadPath));
        }

        String title = ani.getTitle().trim();
        Integer season = ani.getSeason();
        Boolean ova = ani.getOva();

        Config config = ConfigUtil.CONFIG;
        String downloadPath = config.getDownloadPath();
        String ovaDownloadPath = config.getOvaDownloadPath();
        // 按拼音首字母存放
        Boolean acronym = config.getAcronym();
        // 根据季度存放
        Boolean quarter = config.getQuarter();
        Boolean fileExist = config.getFileExist();
        if (ova && StrUtil.isNotBlank(ovaDownloadPath)) {
            downloadPath = ovaDownloadPath;
        }
        if (acronym) {
            String pinyin = PinyinUtil.getPinyin(title);
            String s = pinyin.substring(0, 1).toUpperCase();
            if (ReUtil.isMatch("^\\d$", s)) {
                s = "0";
            } else if (!ReUtil.isMatch("^[a-zA-Z]$", s)) {
                s = "#";
            }
            downloadPath += "/" + s;
        } else if (quarter) {
            Integer year = ani.getYear();
            Integer month = ani.getMonth();
            downloadPath = StrFormatter.format("{}/{}-{}", downloadPath, year, String.format("%02d", month));
        }
        if (ova) {
            return List.of(new File(downloadPath + "/" + title));
        }

        String seasonFileName = "";
        String seasonName = config.getSeasonName();
        if ("Season 1".equals(seasonName)) {
            seasonFileName = StrFormatter.format("Season {}", season);
        }
        if ("S01".equals(seasonName)) {
            seasonFileName = StrFormatter.format("S{}", String.format("%02d", season));
        }

        File file = new File(StrFormatter.format("{}/{}/{}", downloadPath, title, seasonFileName));
        List<File> files = new ArrayList<>();
        if (!fileExist) {
            files.add(file);
            return files;
        }
        File aniFile = new File(downloadPath + "/" + title);
        if (!aniFile.exists()) {
            files.add(file);
            return files;
        }

        File[] seasonFiles = ObjectUtil.defaultIfNull(aniFile.listFiles(), new File[]{});
        for (File seasonFile : seasonFiles) {
            if (!seasonFile.isDirectory()) {
                continue;
            }
            String name = seasonFile.getName();
            String s1 = ReUtil.get("^[a-zA-Z]+", name, 0);
            if (StrUtil.isBlank(s1)) {
                continue;
            }
            if ((!s1.equalsIgnoreCase("S")) && (!s1.equalsIgnoreCase("Season"))) {
                continue;
            }
            String s = ReUtil.get("\\d+$", name, 0);
            if (StrUtil.isBlank(s)) {
                continue;
            }
            if (!NumberUtil.isNumber(s)) {
                continue;
            }
            Integer sInt = Integer.parseInt(s);
            if (!NumberUtil.equals(sInt, season)) {
                continue;
            }
            files.add(seasonFile);
        }
        files.add(file);
        return files;
    }

    /**
     * 登录 qBittorrent
     *
     * @return
     */
    public static synchronized Boolean login() {
        ThreadUtil.sleep(1000);
        Config config = ConfigUtil.CONFIG;
        String downloadPath = config.getDownloadPath();
        if (StrUtil.isBlank(downloadPath)) {
            log.warn("下载位置未设置");
            return false;
        }
        return baseDownload.login(ConfigUtil.CONFIG);
    }

    /**
     * 下载
     *
     * @param ani
     * @param item
     * @param savePath
     * @param torrentFile
     */
    public static synchronized void download(Ani ani, Item item, String savePath, File torrentFile) {
        String name = item.getReName();
        Boolean ova = ani.getOva();
        Boolean master = item.getMaster();
        String subgroup = item.getSubgroup();

        Config config = ConfigUtil.CONFIG;
        Boolean backRss = config.getBackRss();

        if (!torrentFile.exists()) {
            log.error("种子下载出现问题 {} {}", name, torrentFile.getAbsolutePath());
            MessageUtil.send(ConfigUtil.CONFIG, ani,
                    StrFormatter.format("种子下载出现问题 {} {}", name, torrentFile.getAbsolutePath()),
                    MessageEnum.ERROR
            );
            return;
        }
        ThreadUtil.sleep(1000);
        savePath = savePath.replace("\\", "/");


        String text = StrFormatter.format("[{}] {} 已更新", subgroup, name);
        if (backRss && !ani.getBackRssList().isEmpty()) {
            text = StrFormatter.format("({}) {}", master ? "主RSS" : "备用RSS", text);
        }
        MessageUtil.send(ConfigUtil.CONFIG, ani, text, MessageEnum.DOWNLOAD_START);

        try {
            if (baseDownload.download(item, savePath, torrentFile, ova)) {
                return;
            }
        } catch (Exception e) {
            String message = ExceptionUtil.getMessage(e);
            log.error(message, e);
        }
        log.error("{} 添加失败，疑似为坏种", name);
        MessageUtil.send(ConfigUtil.CONFIG, ani,
                StrFormatter.format("{} 添加失败，疑似为坏种", name),
                MessageEnum.ERROR);
    }

    /**
     * 获取任务列表
     *
     * @return
     */
    public static synchronized List<TorrentsInfo> getTorrentsInfos() {
        ThreadUtil.sleep(1000);
        return baseDownload.getTorrentsInfos();
    }

    /**
     * 删除已完成任务
     *
     * @param torrentsInfo
     */
    public static synchronized void delete(TorrentsInfo torrentsInfo) {
        Config config = ConfigUtil.CONFIG;
        Boolean delete = config.getDelete();

        TorrentsInfo.State state = torrentsInfo.getState();
        String name = torrentsInfo.getName();

        if (Objects.isNull(state)) {
            return;
        }
        if (!List.of(
                TorrentsInfo.State.pausedUP.name(),
                TorrentsInfo.State.stoppedUP.name()
        ).contains(state.name())) {
            return;
        }

        if (delete) {
            log.info("删除已完成任务 {}", name);
            ThreadUtil.sleep(1000);
            baseDownload.delete(torrentsInfo);
        }
    }

    /**
     * 重命名
     *
     * @param torrentsInfo
     */
    public static synchronized void rename(TorrentsInfo torrentsInfo) {
        Config config = ConfigUtil.CONFIG;
        Boolean rename = config.getRename();
        if (rename) {
            ThreadUtil.sleep(1000);
            baseDownload.rename(torrentsInfo);
        }
    }

    public static Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        if (StrUtil.isBlank(tags)) {
            return false;
        }
        try {
            return baseDownload.addTags(torrentsInfo, tags);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return false;
    }

    public static synchronized void load() {
        Config config = ConfigUtil.CONFIG;
        String download = config.getDownload();
        ClassUtil.scanPackage("ani.rss.download")
                .stream()
                .filter(aClass -> !aClass.isInterface())
                .filter(aClass -> aClass.getSimpleName().equals(download))
                .map(aClass -> (BaseDownload) ReflectUtil.newInstance(aClass))
                .findFirst()
                .ifPresent(TorrentUtil::setBaseDownload);
        BaseDownload.renameCache.clear();
        log.info("下载工具 {}", download);
    }

}
