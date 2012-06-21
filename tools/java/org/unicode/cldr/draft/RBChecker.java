package org.unicode.cldr.draft;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.unicode.cldr.util.Factory;
import org.unicode.cldr.util.SupplementalDataInfo;
import org.unicode.cldr.util.With;

import com.ibm.icu.dev.test.util.BagFormatter;
import com.ibm.icu.impl.Row;
import com.ibm.icu.impl.Row.R2;
import com.ibm.icu.impl.UCharacterProperty;
import com.ibm.icu.impl.Utility;
import com.ibm.icu.text.UForwardCharacterIterator;
import com.ibm.icu.text.UTF16;
import com.ibm.icu.text.UnicodeSet;

/**
 * Compares the contents of ICU data output.
 * @author markdavis
 *
 */
public class RBChecker {
    private static final boolean DEBUG = false;

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Args required: -t <icu_data_dir> <cldr_dir> <locales_regex>" +
                    "           or: -c <icu_data_dir> <icu_data_dir_2> <locales_regex>"
                );
        }
        String option = args[0];
        String dir1 = args[1];
        String dir2 = args[2];
        String regex = args[3];
        long totaltime = System.currentTimeMillis();
        if (option.equals("-t")) {
            System.out.println("Comparing the contents of text files...");
            compareTextFiles(dir1, dir2, regex);
        } else if (option.equals("-c")) {
            System.out.println("Comparing the contents of text files and converter output...");
            compareTextAndConverter(dir1, dir2, regex);
        }
        System.out.println("Total time taken: " + (System.currentTimeMillis() - totaltime));
    }
    
    /**
     * Parses and compares two ICU textfiles.
     * @param dir1
     * @param dir2
     * @param regex
     * @throws IOException
     */
    private static void compareTextFiles(String dir1, String dir2, String regex) throws IOException {
        File localeDir = new File(dir1 + "/locales");
        if (!localeDir.exists()) localeDir = new File(dir1);
        String[] filenames = localeDir.list();
        int same = 0, different = 0;
        for (String filename : filenames) {
            if (!filename.matches(regex +"\\.txt")) continue;
            String locale = filename.substring(0, filename.length() - 4);
            try {
                IcuData oldData = loadDataFromTextfiles(dir1, locale);
                IcuData newData = loadDataFromTextfiles(dir2, locale);
                StringBuffer messages = new StringBuffer();
                if (analyseMatches(oldData, newData, messages)) {
                    System.out.println("=== Differences found for " + locale + " ===");
                    System.out.print(messages);
                    different++;
                } else {
                    same++;
                }
            } catch (FileNotFoundException e) {
                System.err.println(locale + " file not found, skipping");
            }
        }
        System.out.println("Check finished with " + different + " different and " + same + " same locales.");
    }
    
    /**
     * Compares an ICU textfile with the output of the LDMLConverter. This
     * method should only be used for sanity checking because there are several
     * differences between the data structure parsed from an ICU file and the
     * data structure retrieved directly from LDMLConverter, even if the data
     * itself is identical. Also, comparing two ICU textfiles is much faster
     * than loading the output of LDMLConverter.
     * @param icuDir
     * @param cldrDir
     * @param regex
     * @throws IOException
     */
    private static void compareTextAndConverter(String icuDir, String cldrDir, String regex) throws IOException {
        File specialDir = new File(icuDir, "xml/main");
        Factory specialFactory = null;
        if (specialDir.exists()) {
            specialFactory = Factory.make(specialDir.getAbsolutePath(), regex);
        }
        Factory factory = Factory.make(cldrDir, regex);
        SupplementalDataInfo supplementalDataInfo = SupplementalDataInfo.getInstance();
        LdmlLocaleMapper mapper = new LdmlLocaleMapper(factory, specialFactory, supplementalDataInfo);
        int same = 0, different = 0;
        for (String locale : factory.getAvailable()) {
            IcuData oldData = loadDataFromTextfiles(icuDir, locale);
            IcuData newData = mapper.fillFromCLDR(locale);
            StringBuffer messages = new StringBuffer();
            if (analyseMatches(oldData, newData, messages)) {
                System.out.println("=== Differences found for " + locale + " ===");
                System.out.println(messages);
                different++;
            } else {
                same++;
            }
        }
        System.out.println("Check finished with " + different + " different and " + same + " same.");
    }

    /**
     * NOTE: unused. can delete?
     * @param comments
     * @param output
     * @throws IOException
     */
    private static void writeComments(List<R2<MyTokenizer.Type, String>> comments, Appendable output) throws IOException {
        for (R2<MyTokenizer.Type, String> entity : comments) {
            switch (entity.get0()) {
            case LINE_COMMENT:
                output.append("// ").append(entity.get1()).append('\n');
                break;
            case BLOCK_COMMENT:
                output.append("/*").append(entity.get1()).append("*/\n");
                break;
            }
        }
    }

    private static IcuData loadDataFromTextfiles(String icuPath, String locale) throws IOException {
        List<Row.R2<MyTokenizer.Type, String>> comments = new ArrayList<Row.R2<MyTokenizer.Type, String>>();
        IcuData icuData = new IcuData("common/main/" + locale + ".xml", locale, true);
        String filename = icuPath + '/' + locale + ".txt";
        if (new File(filename).exists()) {
            parseRB(filename, icuData, comments);
        } else {
            for (String dir : With.array("locales", "lang", "region", "curr", "zone")) {
                String source = icuPath + '/' + dir + '/' + locale + ".txt";
                parseRB(source, icuData, comments);
            }
        }
        return icuData;
    }

    /**
     * Computes lists of all differences between two sets of IcuData.
     * @param oldData
     * @param newData
     */
    private static boolean analyseMatches(IcuData oldData, IcuData newData, StringBuffer buffer) {
        boolean hasDifferences = false;
        Set<String> missing = new TreeSet<String>(oldData.keySet());
        missing.removeAll(newData.keySet());
        if (missing.size() > 0) {
            buffer.append("Missing paths:\n");
            printAllInSet(oldData, missing, buffer);
            hasDifferences = true;
        }
        Set<String> extra = new TreeSet<String>(newData.keySet());
        extra.removeAll(oldData.keySet());
        if (extra.size() > 0) {
            buffer.append("Extra paths:\n");
            printAllInSet(newData, extra, buffer);
            hasDifferences = true;
        }
        Set<String> common = new TreeSet<String>(oldData.keySet());
        common.retainAll(newData.keySet());
        for (String rbPath : common) {
            List<String[]> oldValues = oldData.get(rbPath);
            List<String[]> newValues = newData.get(rbPath);
            // Print out any value differences.
            if (valuesDiffer(oldValues, newValues)) {
                buffer.append(rbPath + " contains differences:\n");
                buffer.append("\tOld: ");
                printValues(oldValues, buffer);
                buffer.append("\tNew: ");
                printValues(newValues, buffer);
                hasDifferences = true;
            }
        }
        return hasDifferences;
    }
    
    private static void printAllInSet(IcuData icuData, Set<String> paths, StringBuffer buffer) {
        for (String path : paths) {
            buffer.append("\t" + path + " = ");
            printValues(icuData.get(path), buffer);
        }
    }
    
    private static void printValues(List<String[]> values, StringBuffer buffer) {
        // Enclose both numbers and strings in quotes for simplicity.
        for (String[] array : values) {
            if (array.length == 1) {
                buffer.append('"' + array[0] + '"');
            } else {
                buffer.append("[");
                for (String value : array) {
                    buffer.append('"' + value + "\", ");
                }
                buffer.append("]");
            }
            buffer.append(", ");
        }
        buffer.append('\n');
    }
    
    private static boolean valuesDiffer(List<String[]> oldValues, List<String[]> newValues) {
        // TODO: handle arrays with multiple values.
        if (oldValues.size() != newValues.size()) return true;
        boolean differ = false;
        for (int i=0; i < oldValues.size(); i++) {
            String[] oldArray = oldValues.get(i);
            String[] newArray = newValues.get(i);
            if (oldArray.length != newArray.length) {
                differ = true;
                break;
            }
            for (int j = 0; j < oldArray.length; j++) {
                if (!oldArray[j].equals(newArray[j])) {
                    differ = true;
                    break;
                }
            }
        }
        return differ;
    }


    /**
     * Parse an ICU resource bundle into key,value items
     * 
     * @param filename
     * @param output
     * @param comments
     */
    static void parseRB(String filename, IcuData icuData, List<R2<MyTokenizer.Type, String>> comments) throws IOException {
        BufferedReader in = null;
        File file = new File(filename);
        String coreFile = file.getName();
        if (!coreFile.endsWith(".txt")) {
            throw new IllegalArgumentException("missing .txt in: " + filename);
        }
        coreFile = coreFile.substring(0, coreFile.length()-4);
        // redo this later on to use fixed PatternTokenizer
        in = BagFormatter.openUTF8Reader("", filename);
        MyTokenizer tokenIterator = new MyTokenizer(in);
        StringBuffer tokenText = new StringBuffer();
        List<String> oldPaths = new ArrayList<String>();
        String lastLabel = null;
        String path = "";
        /*
         * AuxExemplarCharacters{
         * "[á à ă â å ä ã ā æ ç é è ĕ ê ë ē í ì ĭ î ï ī ñ ó ò ŏ ô ö ø ō œ ú ù ŭ û ü ū ÿ"
         * "]" } ExemplarCharacters{
         * "[a b c d e f g h i j k l m n o p q r s t u v w x y z]"}
         * ExemplarCharactersCurrency
         * {"[a b c č d e f g h i j k l ł m n o º p q r s t u v w x y z]"}
         * ExemplarCharactersIndex
         * {"[A B C D E F G H I J K L M N O P Q R S T U V W X Y Z]"}
         * ExemplarCharactersPunctuation{"[\- ‐ – — , ; \: ! ? . … ' ‘ ’ \"
         * “ ” ( ) \[ \] @ * / \& # † ‡ ′ ″ §]"}
         */
        MyTokenizer.Type lastToken = null;
        List<String> arrayValues = null;
        main: while (true) {
            MyTokenizer.Type nextToken = tokenIterator.next(tokenText);
            if (DEBUG)
                System.out.println(nextToken + "\t" + tokenText);
            switch (nextToken) {
            case BLOCK_COMMENT:
            case LINE_COMMENT:
                if (comments != null) {
                    comments.add(Row.of(nextToken, tokenText.toString()));
                }
                continue;
            case DONE:
                if (oldPaths.size() != 0) {
                    throw new IllegalArgumentException("missing }");
                }
                break main;
            case ID:
                lastLabel = lastLabel == null ? tokenText.toString() : lastLabel + tokenText;
                break;
            case QUOTED:
                if (lastLabel == null) {
                    lastLabel = tokenText.toString();
                } else {
                    // Remove consecutive quotes.
                    lastLabel += tokenText;
                }
                break;
            case OPEN_BRACE:
                if (lastToken == MyTokenizer.Type.OPEN_BRACE) {
                    throw new IllegalArgumentException("{{");
                }
                // Check for array-type values.
                if(lastToken == MyTokenizer.Type.COMMA || lastToken == MyTokenizer.Type.CLOSE_BRACE) {
                   arrayValues = new ArrayList<String>();
                } else {
                    oldPaths.add(path);
                    if (lastLabel.contains(":") && !lastLabel.contains(":int") && !lastLabel.contains(":alias")
                            || path.endsWith("/relative")) {
                        lastLabel = '"' + lastLabel + '"';
                    }
                    path = path + "/" + lastLabel;
                }
                lastLabel = null;
                break;
            case CLOSE_BRACE:
                if (lastLabel != null) {
                    addPath(path, lastLabel, icuData);
                    lastLabel = null;
                }
                if (arrayValues == null) {
                    path = oldPaths.remove(oldPaths.size() - 1);
                } else {
                    // Value array closed, add it to the path.
                    String[] array = new String[0];
                    addPath(path, arrayValues.toArray(array), icuData);
                    arrayValues = null;
                }
                if (DEBUG)
                    System.out.println("POP:\t" + path);
                break;
            case COMMA:
                if (lastToken != MyTokenizer.Type.QUOTED && lastToken != MyTokenizer.Type.ID) {
                    throw new IllegalArgumentException(filename + ", "+ path +": Commas can only occur after values ");
                } else if (lastLabel == null) {
                    throw new IllegalArgumentException(filename + ": Label missing!");
                }
                if (arrayValues != null) {
                    arrayValues.add(lastLabel);
                } else {
                    addPath(path, lastLabel, icuData);
                }
                lastLabel = null;
                break;
            default:
                throw new IllegalArgumentException("Illegal type in " + filename + ": " + nextToken + "\t" + tokenText + "\t" + Utility.hex(tokenText));
            }
            lastToken = nextToken;
        }
        in.close();
    }
    
    private static void addPath(String path, String value, IcuData icuData) {
        addPath(path, new String[]{value}, icuData);
    }
    
    private static void addPath(String path, String[] values, IcuData icuData) {
        path = path.substring(path.indexOf('/', 1));
        // Hack to stop additional copies of the /Version value from being added.
        if (path.equals("/Version") && icuData.containsKey(path)) {
            return;
        }
        icuData.add(path, values);
    }

    /**
     * Replace by updated PatternTokenizer someday
     * @author markdavis
     *
     */
    static class MyTokenizer {
        enum Type {DONE, ID, QUOTED, OPEN_BRACE, CLOSE_BRACE, COMMA, LINE_COMMENT, BLOCK_COMMENT, BROKEN_QUOTE, BROKEN_BLOCK_COMMENT, UNKNOWN}

        private final UForwardCharacterIterator source;
        private final UnicodeSet spaceCharacters = new UnicodeSet("[\\u0000\\uFEFF[:pattern_whitespace:]]");
        private final UnicodeSet idCharacters = new UnicodeSet("[-:%\"'[:xid_continue:]]");
        private final UnicodeSet quoteCharacters = new UnicodeSet("[\"']");

        private int bufferedChar;

        /**
         * @param reader
         */
        public MyTokenizer(Reader reader) {
            this.source = new UReaderForwardCharacterIterator(reader);
        }

        public Type next(StringBuffer tokenText) {
            int cp = getCodePoint();
            // Skip all spaces not in quotes.
            while(cp >= 0 && spaceCharacters.contains(cp)) {
                cp = getCodePoint();
            }
            if (cp == -1) {
                return Type.DONE;
            }
            tokenText.setLength(0);
            if (cp == '/') {
                cp = getCodePoint();
                if (cp == '/') { // line comment
                    while (true) {
                        cp = getCodePoint();
                        if (cp == '\n' || cp < 0) {
                            return Type.LINE_COMMENT;
                        }
                        tokenText.appendCodePoint(cp);
                    }
                } else if (cp == '*') { // block comment
                    while (true) {
                        cp = getCodePoint();
                        if (cp < 0) {
                            return Type.BROKEN_BLOCK_COMMENT;
                        }
                        while (cp == '*') {
                            int cp2 = getCodePoint();
                            if (cp2 < 0) {
                                return Type.BROKEN_BLOCK_COMMENT;
                            } else if (cp2 == '/') {
                                return Type.BLOCK_COMMENT;
                            }
                            tokenText.appendCodePoint(cp);
                            cp = cp2;
                        }
                        tokenText.appendCodePoint(cp);
                    }
                } else {
                    throw new IllegalArgumentException("/ can only be in quotes or comments");
                }
            }
            if (quoteCharacters.contains(cp)) {
                // Return the text inside and *excluding* the quotes.
                int oldQuote = cp;
                cp = getCodePoint();
                while (cp != oldQuote) {
                    if (cp < 0) {
                        return Type.BROKEN_QUOTE;
                    } else if (cp == '\\') {
                        tokenText.appendCodePoint(cp);
                        cp = getCodePoint();
                        if (cp < 0) {
                            return Type.BROKEN_QUOTE;
                        }
                    }
                    tokenText.appendCodePoint(cp);
                    cp = getCodePoint();
                };
                return Type.QUOTED;
            }
            if (cp == '{') {
                return Type.OPEN_BRACE;
            }
            if (cp == '}') {
                return Type.CLOSE_BRACE;
            }
            if (cp == ',') {
                return Type.COMMA;
            }
            if (idCharacters.contains(cp)) {
                while (true) {
                    tokenText.appendCodePoint(cp);
                    cp = getCodePoint();
                    if (cp < 0 || !idCharacters.contains(cp)) {
                        pushCodePoint(cp);
                        return Type.ID;
                    }
                }
            }
            tokenText.appendCodePoint(cp);
            return Type.UNKNOWN;
        }

        int getCodePoint() {
            if (bufferedChar >= 0) {
                int result = bufferedChar;
                bufferedChar = -1;
                return result;
            }
            return source.nextCodePoint();
        }
        void pushCodePoint(int codepoint) {
            if (bufferedChar >= 0) {
                throw new IllegalArgumentException("Cannot push twice");
            }
            bufferedChar = codepoint;
        }
    }

    public static class UReaderForwardCharacterIterator implements UForwardCharacterIterator {
        private Reader reader;
        private int bufferedChar = -1;

        /**
         * @param reader
         */
        public UReaderForwardCharacterIterator(Reader reader) {
            this.reader = reader;
        }

        /* (non-Javadoc)
         * @see com.ibm.icu.text.UForwardCharacterIterator#next()
         */
        public int next() {
            if (bufferedChar >= 0) {
                int temp = bufferedChar;
                bufferedChar = -1;
                return temp;
            }
            try {
                return reader.read();
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            }
        }

        /* (non-Javadoc)
         * @see com.ibm.icu.text.UForwardCharacterIterator#nextCodePoint()
         */
        public int nextCodePoint(){
            int ch1 = next();
            if(UTF16.isLeadSurrogate((char)ch1)){
                int bufferedChar = next();
                if(UTF16.isTrailSurrogate((char)bufferedChar)){
                    return UCharacterProperty.getRawSupplementary((char)ch1,
                            (char)bufferedChar);
                }
            }
            return ch1;
        }
    }
}
