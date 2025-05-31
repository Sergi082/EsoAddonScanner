package eso.mycode;


import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class App {
    // get OS username
    static String username = System.getProperty("user.name");
    // create addons directory var
    static String addonsPath = String.format("C:/Users/%s/Documents/Elder Scrolls Online/live/AddOns", username);

    public static void main(String[] args) {
        System.out.println("ESO addon updater стартует....");
        System.out.println("Имя пользователя: " + username);
        //check path exists
        Path path = Paths.get(addonsPath);
        if (Files.exists(path)) {
            System.out.println("Addons Path: " + addonsPath);
        } else {
            System.out.println("Путь не существует");
            return;
        }

        Scanner scanner = new Scanner(System.in);
        System.out.println("\u001B[31m" + "Для продолжения работы нажмите Enter..." + "\u001B[0m");
        scanner.nextLine();

        long startTime = System.currentTimeMillis();
        System.out.println("Собираю информацию об установленных аддонах...");

        List<Addon> list = new ArrayList<>();

        try (Stream<Path> dirs = Files.list(path)
                .filter(Files::isDirectory)) {
            //worker - метод для обработки директорий аддонов и создания списка для проверки обновлений
            dirs.forEach(dir -> worker(dir, list));
        } catch (IOException e) {
            System.err.println("Ошибка при чтении директории: " + e.getMessage());
        }

        System.out.println(list.size() + " аддонов присутствуют в базе и готовы к проверке обновлений");
        System.out.println("Запускаю поиск обновлений...");

        //list.forEach(App::updater); // последовательное скачивание если 1-2 не работает для отладки


        // 1. Создаём массив CompletableFuture для каждой задачи
        CompletableFuture<?>[] futures = list.parallelStream()
                .map(item -> CompletableFuture.runAsync(() -> App.updater(item)))
                .toArray(CompletableFuture[]::new);

        // 2. Ждём завершения ВСЕХ задач
        CompletableFuture.allOf(futures).join();

        // 3. Теперь можно безопасно вызывать extractor()
        extractor();

        System.out.println("Время выполнения: " +
                (System.currentTimeMillis() - startTime) / 1000.0 + " с");

        System.out.println("\u001B[31m" + "Для завершения работы нажмите Enter..." + "\u001B[0m");
        scanner.nextLine();
    }

    private static void extractor() {
        Path directory = Paths.get("Updates/");
        if (Files.exists(directory)) {
            System.out.println("\u001B[31m" + "Требуется обновление аддонов!" + "\u001B[0m");
            File[] zipFiles = directory.toFile().listFiles((dir, name) -> name.endsWith(".zip"));

            for (int i = 0; i < Objects.requireNonNull(zipFiles).length; i++) {
                String name = addonsPath + "/" + zipFiles[i].getName();
                // путь к каталогу для очистки и замены файлов
                name = name.substring(0, name.length() - 4);

                //если имя файла DolgubonsLazyWritCreator скопировать Languages/ru.lua в Updates а потом вернуть его
                if (zipFiles[i].getName().equals("DolgubonsLazyWritCreator.zip")) {
                    try {
                        Files.copy(Paths.get(addonsPath + "/DolgubonsLazyWritCreator/Languages/ru.lua"), Paths.get("Updates/ru.lua"),
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    } catch (IOException e) {
                        System.out.println(e.getMessage());
                    }
                }

                // очистка
                deleteAll(new File(name));

                //распаковка архива
                try {
                    new ZipFile("Updates/" + zipFiles[i].getName()).extractAll(addonsPath);
                } catch (ZipException e) {
                    System.out.println(e.getMessage());
                }

                if (zipFiles[i].getName().equals("DolgubonsLazyWritCreator.zip")) {
                    try {
                        Files.copy(Paths.get("Updates/ru.lua"), Paths.get(addonsPath + "/DolgubonsLazyWritCreator/Languages/ru.lua"),
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    } catch (IOException e) {
                        System.out.println(e.getMessage());
                    }
                }
            }
        }
        // очистка
        deleteAll(new File("Updates/"));
    }

    private static void updater(Addon addon) {
        String esouiPath = "https://www.esoui.com/downloads/info" + addon.id(); //страница доступа к аддону
        String version = "";
        long date = 0;
        String status;

        //jsoup - библиотека для парсинга HTML-документов
        try {
            Document doc = Jsoup.connect(esouiPath).userAgent("Mozilla/5.0").get();

            // Находим тег <div id="safe">
            //<div id="safe">Updated: 04/18/25 10:35 AM</div>
            Elements elements1 = doc.select("#safe");
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yy");
            LocalDate dateLocal = LocalDate.parse(elements1.text().substring(9, 17), formatter);
            date = dateLocal.atStartOfDay().atZone(ZoneId.systemDefault())
                    .toInstant().getEpochSecond() * 1000; // *1000 для учета часов, не только даты

            // Находим тег <div id="version">
            //<div id="version">Version: 2025.04.18-2</div>
            Elements elements2 = doc.select("#version");
            version = elements2.text().substring(9);

            // Флаг статус проверки обновления
            if (addon.version().equals(version)) {  // если версии одинаковы обновление не требуется
                status = "Updated!";
            } else {
                if (addon.date() < date) {  // если дата создания файла меньше даты парсинга с сайта, то обновление требуется
                    status = "Need update!";
                } else {
                    status = "Updated!";
                }
            }

            if (status.equals("Updated!")) {
                System.out.println(addon + " " + "\u001B[32m" + status + "\u001B[0m");
            } else {
                // Запускаем downloader
                //ищем прямую ссылку на скачивание
                /*<div id="download">
	            <div id="size">(377 Kb)</div>
	            <a href="/downloads/download3512-PersonalAssistantBankingConsumeJunkLootRepairWorkerMasteroshi430sbranch">Download</a>
                </div> */
                String href = doc.select("#download #size ~ a").attr("href");
                //формируем урл для скачивания
                String url = "https://www.esoui.com".concat(href);
                // если есть косяк в url
                if (url.contains("javascript")) {
                    throw new IOException(addon+ " " + url);
                }

                // скачиваем файл
                downloader(addon, url);
                status = "Update download!------------------->";
                System.out.println(addon + " " + "\u001B[31m" + status + "\u001B[0m");
            }
        } catch (IOException e) {
            System.err.println("Ошибка при получении страницы: " + e.getMessage());
        }
    }

    private static void downloader(Addon addon, String url) {
        //Метод для скачивания файлов и обновления аддона
        String href = "";
        try {
            Document doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get();
            /*<div class="manuallink">
            Problems with the download? <a href="https://cdn.esoui.com/downloads/file56/LibMediaProvider-1.0 r33.zip?174107645515">Click here</a>.
            </div>*/
            href = doc.select("div.manuallink a").attr("href");

        } catch (IOException e) {
            System.out.println(e.getMessage());
        }

        Path directory = Paths.get("Updates/");
        try {
            if (!Files.exists(directory)) {
                Files.createDirectory(directory);
            }

            URL myurl = new URL(href);
            try (BufferedInputStream bis = new BufferedInputStream(myurl.openStream());
                 FileOutputStream fos = new FileOutputStream("Updates/" + addon.name() + ".zip")) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = bis.read(buffer)) != -1) {
                    fos.write(buffer, 0, count);
                }
            }
        } catch (IOException e) {
            System.err.println("Ошибка при работе с папкой: " + e.getMessage());
        }
    }

    private static void worker(Path dir, List<Addon> list) {
        //метод для получения информации об установленных аддонах (версии....) заполняет лист аддонами для проверки
        String versionAddon = "None";

        // файл в котором хранится версия txt или addon
        Path filePath = Path.of(dir.toString(), dir.getFileName().toString() + ".txt");

        if (!Files.exists(filePath)) {
            filePath = Path.of(dir.toString(), dir.getFileName().toString() + ".ADDON");
        }

        if (!Files.exists(filePath)) {
            System.out.println("Файл с версией в директории " + dir.getFileName() + " не найден!");
            return;
        }

        try (Stream<String> lines = Files.lines(filePath, StandardCharsets.ISO_8859_1)) { // кодировка, что бы убрать MalformedInputException
            // получаем string версию аддона
            for (String line : lines.toList()) {
                if (line.startsWith("## Version:")) {
                    versionAddon = line.substring(12);
                } else if (line.startsWith("## AddOnVersion:")) {
                    if (versionAddon.equals("None")) versionAddon = line.substring(17);
                }
            }

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

        // в data.csv лежат названия аддонов и id для поиска на сайте, добавлять id необходимо в ручную
        int id = -1;
        try (BufferedReader reader = new BufferedReader(new FileReader("./data.csv"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(";");
                if (dir.getFileName().toString().equals(values[0])) {
                    id = Integer.parseInt(values[1]);
                    break;
                }
            }
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }

        if (id != -1) {
            Addon addon = new Addon(dir.getFileName().toString(), versionAddon, filePath.toFile().lastModified(), id);
            list.add(addon);
        } else {
            System.out.println(dir.getFileName().toString() + " отсутствует в базе data.csv");
        }
    }

    private static void deleteAll(File path) {
        // рекурсивное удаление каталого с его содержимым
        if (path.isDirectory()) {
            for (File f : Objects.requireNonNull(path.listFiles())) {
                if (f.isDirectory()) deleteAll(f);
                else f.delete();
            }
        }
        path.delete();
    }
}

record Addon(String name, String version, long date, int id) {
    // создаем класс запись для дальнейшей обработки
    @Override
    public String toString() {
        // с преобразованием long в дату
        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(date), ZoneId.systemDefault());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yy");
        String dateString = dateTime.format(formatter);
        return "{ " + name + ", version: " + version +
                ", date: " + dateString + ", id: " + id + " }";
    }
}