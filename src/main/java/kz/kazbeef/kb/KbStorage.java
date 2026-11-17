package kz.kazbeef.kb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Markdown-версии документов на диске: один файл {id}.md на документ. */
@Component
public class KbStorage {

    private static final Logger log = LoggerFactory.getLogger(KbStorage.class);

    private final Path dir;

    public KbStorage(KbProperties props) {
        this.dir = Path.of(props.dir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Каталог базы знаний {} недоступен: {}", dir, e.toString());
        }
    }

    public String fileName(long id) {
        return id + ".md";
    }

    /** Запись через временный файл: читатель никогда не увидит наполовину записанный документ. */
    public long write(long id, String markdown) {
        try {
            Path target = dir.resolve(fileName(id));
            Path tmp = dir.resolve(fileName(id) + ".tmp");
            Files.writeString(tmp, markdown, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return Files.size(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить Markdown документа " + id, e);
        }
    }

    public String read(long id) {
        try {
            return Files.readString(dir.resolve(fileName(id)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не найден Markdown документа " + id, e);
        }
    }

    public boolean exists(long id) {
        return Files.isRegularFile(dir.resolve(fileName(id)));
    }

    public void delete(long id) {
        try {
            Files.deleteIfExists(dir.resolve(fileName(id)));
        } catch (IOException e) {
            log.warn("Не удалось удалить файл документа {}: {}", id, e.toString());
        }
    }
}
