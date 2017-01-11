package com.shtrih.jpos.fiscalprinter;

import com.shtrih.fiscalprinter.FontNumber;
import com.shtrih.fiscalprinter.command.IPrinterEvents;
import com.shtrih.fiscalprinter.command.LongPrinterStatus;
import com.shtrih.fiscalprinter.command.PrinterCommand;
import com.shtrih.fiscalprinter.command.PrinterConst;
import com.shtrih.fiscalprinter.command.PrinterStatus;
import com.shtrih.util.SysUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class TextReportPrinter implements IPrinterEvents {
	/////////////////////////////////////////////////////////////////////////////
	// EJ report type constants

	private boolean wasNoPaper = false;
	private final FiscalPrinterImpl printer;

	public TextReportPrinter(FiscalPrinterImpl printer) {
		this.printer = printer;
	}

	@Override
	public void init() {
	}

	@Override
	public void done() {
	}

	@Override
	public void beforeCommand(PrinterCommand command) {
	}

	@Override
	public void printerStatusRead(PrinterStatus status) {
	}

	@Override
	public void afterCommand(PrinterCommand command) {
		if (command.getResultCode() == 0x6B) {
			wasNoPaper = true;
		}
	}

	public void start() {
		printer.getPrinter().addEvents(this);
		printer.setTextDocumentFilterEnablinessTo(false);
	}

	public void stop() {
		printer.getPrinter().removeEvents(this);
        printer.setTextDocumentFilterEnablinessTo(true);
	}

	public int readDayNumber() throws Exception {
		int dayNumber;
		LongPrinterStatus status = printer.getPrinter().readLongStatus();
		if (status.getRegistrationNumber() > 0) {
			dayNumber = status.getDayNumber();
		} else {
			dayNumber = printer.getPrinter().readOperationRegister(159);
		}
		dayNumber = dayNumber + 1;
		if (dayNumber == 10000)
			dayNumber = 1;
		return dayNumber;
	}

	private List<String> loadLines() throws Exception {
		String fileName = SysUtils.getFilesPath() + printer.getParams().textReportFileName;
		List<String> result = new ArrayList<String>();
		BufferedReader reader = new BufferedReader(new FileReader(fileName));
		String line;
		try {
			while ((line = reader.readLine()) != null) {
				result.add(line);
			}
		} finally {
			reader.close();
		}
		return result;
	}

	public static final String SINN = "ИНН";
	public static final String SZReport = "СУТОЧНЫЙ ОТЧЕТ С ГАШЕНИЕМ";

	private int getDocumentNumber(String line) throws Exception {
		if (line.contains(SINN) && line.contains("№")) {
			int startIndex = line.indexOf("№") + 1;
			String number = line.substring(startIndex, startIndex + 4);
			return Integer.parseInt(number);
		}
		return 0;
	}

	// СУТОЧНЫЙ ОТЧЕТ С ГАШЕНИЕМ #0010
	private int getDayNumber(String line) throws Exception {
		if (line.contains(SZReport)) {
			int startIndex = line.length() - 4;
			String number = line.substring(startIndex, startIndex + 4);
			return Integer.parseInt(number);
		}
		return -1;
	}

	private boolean isDocumentHeader(String line) {
		return line.contains(SINN);
	}

	private boolean isZReport(String line) {
		return line.contains(SZReport);
	}

	private int findNextDocument(List<String> lines, int index) {
		for (int i = index; i < lines.size(); i++) {
			String line = lines.get(i);
			if (isDocumentHeader(line)) {
				return i;
			}
		}
		return lines.size();
	}

	private int findPrevDocument(List<String> lines, int index) {
		for (int i = index; i >= 0; i--) {
			String line =  lines.get(i);
			if (isDocumentHeader(line))
				return i;
		}
		return index;
	}

	private List<String> copyLines(List<String> lines, int index1, int index2) {
		List<String> result = new ArrayList<String>();
		for (int i = index1; i <= index2; i++) {
			if (i < 0)
				return result;
			if (i >= lines.size())
				return result;
			result.add(lines.get(i));
		}
		return result;
	}

	public void printEJReportDayCurrent(int dayNumber) throws Exception {
		List<String> lines = loadLines();
		int index1 = 0;
		int index2 = lines.size() - 1;
		for (int i = 0; i < lines.size(); i++) {
			String line =  lines.get(i);

			int dayNum = getDayNumber(line);
			if (dayNum == (dayNumber - 1)) {
				index1 = findNextDocument(lines, i + 1);
				break;
			}
			if (dayNum == dayNumber) {
				index2 = findNextDocument(lines, i + 1);
				break;
			}
		}

		List<String> dstLines = copyLines(lines, index1, index2);
		String header = String.format("Контрольная лента Смена № %d", dayNumber);
		dstLines.add(0, header);
		printLines(dstLines);
	}

	private static final int ReceiptBufferLength = 550;

	private int updateIndex(List<String> lines, int index) {
		index++;
		if (wasNoPaper) {
			wasNoPaper = false;
			index = index - ReceiptBufferLength;
			if (index < 0)
				index = 0;
			index = findPrevDocument(lines, index);
		}
		return index;
	}

	private void printLines(List<String> lines) throws Exception {
		int index = 0;
		wasNoPaper = false;
		while (index < lines.size()) {
			String line = lines.get(index);
			printer.getPrinter().printLine(PrinterConst.SMFP_STATION_REC, line, FontNumber.getNormalFont());
			index = updateIndex(lines, index);
		}
		printer.getPrinter().waitForPrinting();
	}

	private int findZReport(List<String> lines, int dayNumber) throws Exception {
		for (int i = 0; i < lines.size(); i++) {
			String line = (String) lines.get(i);
			int dayNum = getDayNumber(line);
			if (dayNum == dayNumber)
				return i;
		}
		return -1;
	}

	public void printEJReportDayNumber(int dayNumber) throws Exception {
		List<String> lines = loadLines();
		int index1 = findZReport(lines, dayNumber - 1);
		if (index1 != -1) {
			index1 = findNextDocument(lines, index1 + 1);
		}
		int index2 = findZReport(lines, dayNumber);
		if (index2 != -1) {
			index2 = findNextDocument(lines, index2 + 1) - 1;
		} else {
			index2 = lines.size() - 1;
		}

		if ((index1 > index2) || ((index1 == -1) && (index2 == -1))) {
			throw new Exception(String.format("Смена № %d не найдена", dayNumber));
		}

		List<String> dst = copyLines(lines, index1, index2);
		String header = String.format("Контрольная лента Смена № %d", dayNumber);
		dst.add(0, header);
		printLines(dst);

	}

	public void printEJReportDocNumber(int docNumber) throws Exception {
		int index1 = -1;
		int index2 = -1;
		List<String> lines = loadLines();
		int lastDocIndex = lines.size();
		for (int i = lines.size() - 1; i >= 0; i--) {
			String line = (String) lines.get(i);
			int docNum = getDocumentNumber(line);
			if (docNum < docNumber)
				break;
			if (docNum == docNumber) {
				if (index2 == -1)
					index2 = lastDocIndex - 1;
				index1 = i;
			}
			lastDocIndex = i;
		}
		if (index1 == -1) {
			throw new Exception(String.format("Документ № %d не найден", docNumber));
		}

		List<String> dst = copyLines(lines, index1, index2);
		String header = String.format("Контрольная лента Документ № %d", docNumber);
		dst.add(0, header);
		printLines(dst);
	}

	public void printEJReportDocRange(int N1, int N2) throws Exception {
		if (N1 > N2) {
			throw new Exception(String.format("Номер первого документа больше второго (%d > %d)", N1, N2));
		}

		if (N1 == N2) {
			printEJReportDocNumber(N1);
			return;
		}

		int index1 = -1;
		int index2 = -1;

		List<String> lines = loadLines();
		for (int i = 0; i < lines.size(); i++) {
			String line = (String) lines.get(i);
			int docNum = getDocumentNumber(line);
			if ((docNum >= N1) && (docNum <= N2)) {
				if (index1 == -1)
					index1 = i;
				index2 = i;
			}
		}
		index2 = findNextDocument(lines, index2 + 1) - 1;
		List<String> dst = copyLines(lines, index1, index2);
		String header = String.format("Контрольная лента Документ с № %d по № %d", N1, N2);
		dst.add(0, header);
		printLines(dst);
	}

}