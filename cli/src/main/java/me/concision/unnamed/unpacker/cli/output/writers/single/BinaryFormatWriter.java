package me.concision.unnamed.unpacker.cli.output.writers.single;

import lombok.extern.java.Log;
import me.concision.unnamed.unpacker.cli.Unpacker;
import me.concision.unnamed.unpacker.cli.output.FormatType;
import org.apache.commons.compress.utils.IOUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * See {@link FormatType#BINARY}
 *
 * @author Concision
 */
@Log
public class BinaryFormatWriter extends SingleFormatWriter {
    @Override
    public void format(Unpacker unpacker, InputStream packagesStream) throws IOException {
        log.info("Writing extracted Packages.bin");
        this.open(unpacker);

        try {
            IOUtils.copy(packagesStream, outputStream);
            outputStream.flush();
        } finally {
            this.close();
        }
    }
}