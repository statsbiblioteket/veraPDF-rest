/**
 *
 */
package org.verapdf.rest.resources;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.verapdf.core.ModelParsingException;
import org.verapdf.core.VeraPDFException;
import org.verapdf.features.FeatureFactory;
import org.verapdf.metadata.fixer.FixerFactory;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.ValidationResult;
import org.verapdf.pdfa.results.ValidationResults;
import org.verapdf.pdfa.validation.profiles.ProfileDirectory;
import org.verapdf.pdfa.validation.profiles.Profiles;
import org.verapdf.pdfa.validation.validators.ValidatorConfig;
import org.verapdf.pdfa.validation.validators.ValidatorFactory;
import org.verapdf.processor.BatchProcessor;
import org.verapdf.processor.FormatOption;
import org.verapdf.processor.ProcessorConfig;
import org.verapdf.processor.ProcessorFactory;
import org.verapdf.processor.TaskType;
import org.verapdf.processor.plugins.PluginsCollectionConfig;
import org.verapdf.processor.reports.BatchSummary;
import org.verapdf.report.HTMLReport;

import javax.ws.rs.Consumes;
import javax.ws.rs.NotSupportedException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.xml.transform.TransformerException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;

/**
 * @author <a href="mailto:carl@openpreservation.org">Carl Wilson</a>
 */
public class ValidateResource {
    private static final ProfileDirectory DIRECTORY = Profiles.getVeraProfileDirectory();
    // java.security.digest name for the MD5 algorithm
    private static final String SHA1_NAME = "SHA-1"; //$NON-NLS-1$
    private static final String WIKI_URL_BASE = "https://github.com/veraPDF/veraPDF-validation-profiles/wiki/"; //$NON-NLS-1$

    {
        VeraGreenfieldFoundryProvider.initialise();
    }

    /**
     * @param profileId                the String id of the Validation profile (1b, 1a, 2b, 2a, 2u, 3b, 3a, or 3u)
     * @param sha1Hex                  the hex String representation of the file's SHA-1 hash
     * @param uploadedInputStream      a {@link java.io.InputStream} to the PDF to be validated
     * @param contentDispositionHeader
     * @return the {@link org.verapdf.pdfa.results.ValidationResult} obtained when validating the uploaded stream
     * against the selected profile.
     *
     * @throws VeraPDFException
     */
    @POST
    @Path("/{profileid}")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public static ValidationResult validate(@PathParam("profileid") String profileId,
                                            @FormDataParam("sha1Hex") String sha1Hex,
                                            @FormDataParam("url") String url,
                                            @FormDataParam("file") InputStream uploadedInputStream,
                                            @FormDataParam("file") final FormDataContentDisposition contentDispositionHeader) throws VeraPDFException {
        // either upload a file or provide an url.
        if (url != null) {
            if (uploadedInputStream != null) {
                throw new VeraPDFException("url != null and file != null");
            } else {
                try {
                    uploadedInputStream = new URL(url).openConnection().getInputStream();
                } catch (IOException e) {
                    throw new VeraPDFException("could not get inputstream from url=" + url, e);
                }
            }
        }
        PDFAFlavour flavour = PDFAFlavour.byFlavourId(profileId);
        MessageDigest sha1 = getDigest();
        DigestInputStream dis = new DigestInputStream(uploadedInputStream, sha1);
        ValidationResult result = ValidationResults.defaultResult();
        try (PDFAParser toValidate = Foundries.defaultInstance().createParser(dis, flavour)) {
            PDFAValidator validator = ValidatorFactory.createValidator(flavour, false);
            result = validator.validate(toValidate);
        } catch (ModelParsingException mpExcep) {
            // TRA: Unsure why sha1 must be tested to determine cause.  Decided that if sha1 not given, then consider sha1 as matching.

            // If no sha1 given or we have the same sha-1 then it's a PDF Box parse error, so
            // treat as non PDF.
            if (sha1Hex == null || sha1Hex.equalsIgnoreCase(Hex.encodeHexString(sha1.digest()))) {
                throw new NotSupportedException(Response.status(Status.UNSUPPORTED_MEDIA_TYPE)
                        .type(MediaType.TEXT_PLAIN).entity("File does not appear to be a PDF.").build(), mpExcep); //$NON-NLS-1$
            }
            throw mpExcep;
        } catch (IOException excep) {
            excep.printStackTrace();
        }
        return result;
    }

    /**
     * @param profileId                the String id of the Validation profile (1b, 1a, 2b, 2a, 2u, 3b, 3a, or 3u)
     * @param sha1Hex                  the hex String representation of the file's SHA-1 hash
     * @param uploadedInputStream      a {@link java.io.InputStream} to the PDF to be validated
     * @param contentDispositionHeader
     * @return the {@link org.verapdf.pdfa.results.ValidationResult} obtained when validating the uploaded stream
     * against the selected profile.
     *
     * @throws VeraPDFException
     */
    @POST
    @Path("/{profileid}")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces({MediaType.TEXT_HTML})
    public static InputStream validateHtml(@PathParam("profileid") String profileId,
                                           @FormDataParam("sha1Hex") String sha1Hex,
                                           @FormDataParam("url") String url,
                                           @FormDataParam("file") InputStream uploadedInputStream,
                                           @FormDataParam("file") final FormDataContentDisposition contentDispositionHeader) throws VeraPDFException {
        long start = new Date().getTime();
        if (url != null) {
            if (uploadedInputStream != null) {
                throw new VeraPDFException("url != null and file != null");
            } else {
                try {
                    uploadedInputStream = new URL(url).openConnection().getInputStream();
                } catch (IOException e) {
                    throw new VeraPDFException("could not get inputstream from url=" + url, e);
                }
            }
        }
        File file;
        try {
            file = File.createTempFile("cache", "");
        } catch (IOException excep) {
            throw new VeraPDFException("IOException creating a temp file", excep); //$NON-NLS-1$
        }
        try (OutputStream fos = new FileOutputStream(file)) {
            IOUtils.copy(uploadedInputStream, fos);
            uploadedInputStream.close();
        } catch (IOException excep) {
            throw new VeraPDFException("IOException creating a temp file", excep); //$NON-NLS-1$
        }

        PDFAFlavour flavour = PDFAFlavour.byFlavourId(profileId);
        ValidatorConfig validConf = ValidatorFactory.createConfig(flavour, false, 100);
        ProcessorConfig config = createValidateConfig(validConf);

        byte[] htmlBytes = new byte[0];
        try (ByteArrayOutputStream xmlBos = new ByteArrayOutputStream()) {
            BatchSummary summary = processFile(file, config, xmlBos);
            htmlBytes = getHtmlBytes(xmlBos.toByteArray(), summary);
        } catch (IOException | TransformerException excep) {
            throw new VeraPDFException("Some Java Exception while validating", excep); //$NON-NLS-1$
            // TODO Auto-generated catch block
        }
        return new ByteArrayInputStream(htmlBytes);
    }

    private static MessageDigest getDigest() {
        try {
            return MessageDigest.getInstance(SHA1_NAME);
        } catch (NoSuchAlgorithmException nsaExcep) {
            // If this happens the Java Digest algorithms aren't present, a
            // faulty Java install??
            throw new IllegalStateException(
                    "No digest algorithm implementation for " + SHA1_NAME + ", check you Java installation.", nsaExcep); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static byte[] getHtmlBytes(byte[] xmlBytes, BatchSummary summary) throws IOException, TransformerException {
        try (InputStream xmlBis = new ByteArrayInputStream(xmlBytes);
             ByteArrayOutputStream htmlBos = new ByteArrayOutputStream()) {
            // FIXME:  Upgrading from 0.15 to 1.13.6 caused the following line to fail.

            // HTMLReport.writeHTMLReport(xmlBis, htmlBos, summary, WIKI_URL_BASE, false);

            // tra@kb.dk have made it compile by just hardcoding a dummy value as we do not need this functionality.
            // A proper fix is needed.

            HTMLReport.writeHTMLReport(xmlBis, htmlBos, false, WIKI_URL_BASE, false);
            return htmlBos.toByteArray();
        }

    }

    private static ProcessorConfig createValidateConfig(ValidatorConfig validConf) {
        return ProcessorFactory.fromValues(validConf, FeatureFactory.defaultConfig(),
                PluginsCollectionConfig.defaultConfig(), FixerFactory.defaultConfig(), EnumSet.of(TaskType.VALIDATE));
    }

    private static BatchSummary processFile(File file, ProcessorConfig config, OutputStream mrrStream)
            throws VeraPDFException {
        List<File> files = Arrays.asList(file);
        BatchSummary summary = null;
        try (BatchProcessor processor = ProcessorFactory.fileBatchProcessor(config)) {
            summary = processor.process(files,
                    ProcessorFactory.getHandler(FormatOption.MRR, false, mrrStream, 100, false));
        } catch (IOException excep) {
        }
        return summary;
    }
}
