# AF-OPDS / AF-OPDS

Android 10 OPDS-сервер для домашней библиотеки на Android TV и ТВ-приставках.

Android 10 OPDS server for a home library on Android TV and TV boxes.

## Возможности / Features

- Читает FB2 из ZIP-архивов, описанных индексом INPX, без распаковки; также индексирует FB2, EPUB, PDF, MOBI, AZW3 и TXT из обычной папки.
- Каталоги OPDS: все книги, авторы, серии, жанры и поиск. Соавторские книги доступны у каждого автора.
- Локальная веб-панель и журнал: `http://IP_ПРИСТАВКИ:8080/`; OPDS: `http://IP_ПРИСТАВКИ:8080/opds`.
- Для INPX выбирайте **папку**, в которой находятся файл `.inpx` и соответствующие ZIP-архивы, а не сам файл `.inpx`.

- Reads FB2 files from ZIP archives described by an INPX index without unpacking; also indexes FB2, EPUB, PDF, MOBI, AZW3, and TXT in a normal directory.
- OPDS catalogs: all books, authors, series, genres, and search. Co-authored books are available under every author.
- Local web panel and log: `http://TV_BOX_IP:8080/`; OPDS: `http://TV_BOX_IP:8080/opds`.
- For INPX, select the **directory** containing the `.inpx` file and matching ZIP archives, not the `.inpx` file itself.

## Первый запуск / First run

1. Подключите диск и откройте AF-OPDS.
2. Нажмите «Выбрать папку с книгами» / **Choose books folder**. В системном окне выберите папку библиотеки.
3. Нажмите «Проверить USB-диск» / **Check USB drive**, затем «Сканировать библиотеку» / **Scan library**.
4. Запустите сервер / **Start server** и откройте OPDS-адрес в программе для чтения.

## Если нет файлового менеджера с SAF / If no SAF file manager is available

На некоторых прошивках Android TV отсутствует системный выбор папок (Storage Access Framework, SAF). Установка обычного файлового менеджера не добавляет SAF автоматически. В этом случае используйте прямой путь к папке.

Some Android TV firmware does not provide the system folder picker (Storage Access Framework, SAF). Installing an ordinary file manager does not automatically add SAF. Use a direct directory path instead.

1. В приложении нажмите «Указать путь к папке USB» / **Specify USB folder path**. То же поле есть в веб-панели по адресу `http://IP_ПРИСТАВКИ:8080/`.
2. Укажите абсолютный путь к **папке** библиотеки, например `/storage/XXXX-XXXX/Books`. Это только пример: идентификатор тома и имя папки зависят от прошивки и диска.
3. Нажмите «Проверить USB-диск» / **Check USB drive**. Сканирование запускайте только после успешной проверки.
4. Для INPX путь должен указывать на папку, содержащую `.inpx` и ZIP-архивы.

1. In the app tap **Specify USB folder path**. The same field is available in the web panel at `http://TV_BOX_IP:8080/`.
2. Enter the absolute path to the library **directory**, for example `/storage/XXXX-XXXX/Books`. This is only an example: the volume ID and directory name depend on the firmware and disk.
3. Tap **Check USB drive**. Start a scan only after the check succeeds.
4. For INPX, the path must point to the directory containing the `.inpx` and ZIP archives.

Прямой путь не обходит права Android. Если проверка сообщает «Нет доступа к папке», прошивка не предоставляет приложению доступный путь к этому диску; нужно выбрать другую точку монтирования или включить/установить системный провайдер документов с SAF.

A direct path does not bypass Android permissions. If the check reports that the directory is inaccessible, the firmware does not expose a readable path for that disk; use a different mount point or enable/install a system document provider with SAF.

## Сборка / Build

Требуются JDK 17 и Android SDK Platform 35. В корне проекта:

JDK 17 and Android SDK Platform 35 are required. From the project root:

```sh
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Ограничения и безопасность / Limitations and security

- Нет аутентификации и TLS. Не открывайте и не пробрасывайте порт 8080 в Интернет; используйте только доверенную домашнюю сеть.
- Каталог остаётся в SQLite после отключения диска, но скачивание книг будет недоступно, пока диск не подключён снова.
- Сканирование выполняется вручную. После изменения пути или установки версии с поддержкой соавторов запустите новое сканирование.
- Очень большой жанр выдаётся одним OPDS-ответом и может медленно открываться в клиенте.

- There is no authentication or TLS. Do not expose or port-forward TCP 8080 to the Internet; use it only on a trusted home LAN.
- The SQLite catalog remains after the disk is unplugged, but book downloads remain unavailable until the disk is connected again.
- Scanning is manual. Run a new scan after changing the path or installing a version that adds co-author support.
- A very large genre is returned as one OPDS response and may open slowly in a client.
