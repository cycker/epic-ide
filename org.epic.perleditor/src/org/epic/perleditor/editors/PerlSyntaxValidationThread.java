/*
 *  PerSyntaxValidationThread.java 
 *
 *  Contributors:
 *                   Igor Alexeiuk <aie at mailru.com>
 *               
 *
 */

package org.epic.perleditor.editors;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;
import java.io.*;

import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IMarker;

import java.util.Map;
import java.util.HashMap;
import java.util.Hashtable;

import org.epic.perleditor.editors.util.PerlExecutableUtilities;
import org.epic.perleditor.PerlEditorPlugin;

public class PerlSyntaxValidationThread extends Thread {
	private static final String PERL_CMD_EXT = "-c";
	private static final String PERL_ERROR_INDICATOR = " line ";

	private static final String[] WARNING_STRINGS =
		{ "possible", "Useless", "may", "better written as" };

	private String text = "";
	private String previousText = "";

	private boolean stopValidation = false;
	private boolean validationTerminated = false;
	private boolean isActive = false;

	private TextEditor fTextEditor;
	private ISourceViewer fSourceViewer;

	private int previousHashCode = 0;
	// Defaults
	private int validationInterval = 2000; //millis
	private int waitForTermination = 400; // millis
	private int maxErrorsShown = 10;

	private String tmpFileName;

	public PerlSyntaxValidationThread(
		TextEditor textEditor,
		ISourceViewer viewer) {
		super();
		fTextEditor = textEditor;
		fSourceViewer = viewer;
		tmpFileName =
			System.getProperty("java.io.tmpdir")
				+ File.separator
				+ "epic"
				+ new Date().getTime();
	}

	public void setText(String text) {
		if (!isActive)
			this.text = text;
	}

	public void setText(String text, boolean forceUpdate) {
		if (!isActive) {
			this.text = text;
		}

		if (forceUpdate) {
			this.previousText = "";
			this.previousHashCode = 0;
		}
	}

	public String getText() {
		return text;
	}

	public void setInterval(int millis) {
		this.validationInterval = millis;
	}

	public int getInterval() {
		return validationInterval;
	}

	public void setErrorsShown(int number) {
		this.maxErrorsShown = number;
	}

	public int getErrorsShown() {
		return maxErrorsShown;
	}

	public void dispose() {
		this.stopValidation = true;

		if (validationTerminated == false) {

			try {
				Thread.sleep(waitForTermination);
			} catch (Exception e) {
			}
		}
	}

	public void run() {
		while (stopValidation == false) {
			try {

				this.isActive = true;
				boolean ret = this.validateSyntax();

				this.previousText = this.text;
				this.isActive = false;

				Thread.sleep(validationInterval);

			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		this.validationTerminated = true;
	}

	public boolean validateSyntax() {

		Process proc = null;

		if (this.previousText.equals(this.text)) {
			return true;
		}

		try {
			IEditorInput input = fTextEditor.getEditorInput();
			IResource resource =
				(IResource) ((IAdaptable) input).getAdapter(IResource.class);

			// Write output file
			FileOutputStream fos = new FileOutputStream(new File(tmpFileName));
			fos.write(text.getBytes());
			fos.flush();
			fos.close();

			// Construct command line parameters
			List cmdList =
				PerlExecutableUtilities.getPerlExecutableCommandLine(
					fTextEditor);
			cmdList.add(PERL_CMD_EXT);
			
			if(PerlEditorPlugin.getDefault().getWarningsPreference()) {
				cmdList.add("-w");
			}
			
			cmdList.add(tmpFileName);

			String[] cmdParams =
				(String[]) cmdList.toArray(new String[cmdList.size()]);

			proc = Runtime.getRuntime().exec(cmdParams);

			InputStream in = proc.getErrorStream();

			StringBuffer content = new StringBuffer();

			byte[] buffer = new byte[1];

			int read;
			while ((read = in.read(buffer)) > 0) {
				content.append(new String(buffer));
			}
			in.close();

			// DEBUG start
			System.out.println("-----------------------------------------");
			System.out.println("           OUTPUT");
			System.out.println("-----------------------------------------");
			System.out.println(content);
			System.out.println("-----------------------------------------");
			// DEBUG END

			String line = null;
			List lines = new ArrayList();
			int index;

			// Compare checksums
			int hashCode = content.toString().hashCode();

			if (previousHashCode == hashCode) {

				return true;
			}

			previousHashCode = hashCode;

			StringTokenizer st = new StringTokenizer(content.toString(), "\n");

			int lineCount = 0;

			while (st.hasMoreTokens()) {
				line = st.nextToken();
				if (line.indexOf("\r") != -1) {
					line = line.substring(0, line.indexOf("\r"));
				}

				lines.add(line);
				if (++lineCount >= maxErrorsShown) {
					break;
				}

			}

			//Delete markers
			resource.deleteMarkers(IMarker.PROBLEM, true, 1);

			// Hash for tracking line severity
			Map lineHash = new Hashtable();

			// Markers have to be added in reverse order
			// Otherwise lower line number will appear at the end of the list
			for (int i = lines.size() - 1; i >= 0; i--) {
				line = (String) lines.get(i);

				// If error message is not from temporary file, skip
				if (line.indexOf(tmpFileName) == -1) {
					continue;
				}

				// Delete filename from error message
				StringBuffer lineSb = new StringBuffer(line);
				replace(lineSb, tmpFileName + " ", "", true);
				line = lineSb.toString();

				if ((index = line.indexOf(PERL_ERROR_INDICATOR)) != -1) {

					// truncatedLIne is the stripped error-line up to the next " " after the line number if present
					// To avoid cluttering with other "." and "," which might occur in the error message
					String truncatedLine = line;
					if (truncatedLine
						.indexOf(" ", index + PERL_ERROR_INDICATOR.length() + 1)
						!= -1) {
						truncatedLine =
							truncatedLine.substring(
								0,
								truncatedLine.indexOf(
									" ",
									index + PERL_ERROR_INDICATOR.length() + 1));
					}

					int endIndex;
					if ((endIndex = truncatedLine.indexOf(".", index)) == -1) {
						endIndex = truncatedLine.indexOf(",", index);
					}

					if (endIndex == -1) {
						continue;
					}

					String lineNr =
						truncatedLine.substring(
							index + PERL_ERROR_INDICATOR.length(),
							endIndex);

					// If there is an addition to the error message
					if (i + 1 < lines.size()) {
						if (((String) lines.get(i + 1)).startsWith(" ")) {
							line += " " + (String) lines.get(i + 1);
						}
					}

					// Check if it's a warning
					boolean isWarning = false;

					for (int x = 0; x < WARNING_STRINGS.length; x++) {
						if (truncatedLine.indexOf(WARNING_STRINGS[x]) != -1) {
							isWarning = true;
						}
					}

					Map attributes = new HashMap(11);

					if (isWarning) {
						attributes.put(
							IMarker.SEVERITY,
							new Integer(IMarker.SEVERITY_WARNING));
					} else {
						attributes.put(
							IMarker.SEVERITY,
							new Integer(IMarker.SEVERITY_ERROR));
					}

					attributes.put(IMarker.MESSAGE, line);

					attributes.put(
						IMarker.LINE_NUMBER,
						new Integer(Integer.parseInt(lineNr)));

					// Check if a marker with a higher severity already exists
					boolean doUnderline;
					Object obj =
						lineHash.get(new Integer(Integer.parseInt(lineNr)));
					if (obj == null) {
						doUnderline = true;
					} else if (
						((Integer) obj).intValue() == IMarker.SEVERITY_ERROR) {
						doUnderline = false;
					} else {
						doUnderline = true;
					}

					if (doUnderline) {
						lineHash.put(
							new Integer(Integer.parseInt(lineNr)),
							isWarning
								? new Integer(IMarker.SEVERITY_WARNING)
								: new Integer(IMarker.SEVERITY_ERROR));
						// Get start and end offset
						int lineOffset = 0;
						try {
							lineOffset =
								fSourceViewer.getDocument().getLineOffset(
									Integer.parseInt(lineNr) - 1);
						} catch (Exception ex) {
							continue;
						}

						int endOfLine = text.indexOf("\n", lineOffset);
						String markerLine;

						if (endOfLine != -1) {
							markerLine = text.substring(lineOffset, endOfLine);
						} else {
							markerLine = text.substring(lineOffset);
						}

						byte[] bytes = markerLine.getBytes();

						int start = 0;
						while (start < bytes.length) {
							if (bytes[start] != '\t' && bytes[start] != ' ') {
								break;
							}
							start++;
						}

						start += lineOffset;

						int end = start + markerLine.trim().length();

						attributes.put(IMarker.CHAR_START, new Integer(start));
						attributes.put(IMarker.CHAR_END, new Integer(end));
					}
					AddEditorMarker ed = new AddEditorMarker(fTextEditor);
					ed.addMarker(attributes, IMarker.PROBLEM);

				}
			}

		} catch (Exception e) {
			e.printStackTrace();
			if (proc != null) {
				killProcess(proc);
			}
			return false;
		} finally {
			try {
				//				Delete tmp file
				new File(tmpFileName).delete();
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}

		return true;

	}

	private void killProcess(Process proc) {
		while (true) {
			try {
				proc.destroy();
				proc.exitValue();
			} catch (Exception ex) {

				continue;
			}
			break;
		}
	}

	// Replaces all instances of the String o with the String n in the
	// StringBuffer orig if all is true, or only the first instance if all is false.
	private static void replace(
		StringBuffer orig,
		String o,
		String n,
		boolean all) {
		if (orig == null || o == null || o.length() == 0 || n == null)
			throw new IllegalArgumentException("Null or zero-length String");

		int i = 0;

		while (i + o.length() <= orig.length()) {
			if (orig.substring(i, i + o.length()).equals(o)) {
				orig.replace(i, i + o.length(), n);
				if (!all)
					break;
				else
					i += n.length();
			} else
				i++;
		}
	}

	/**
	 * Returns the resource on which to create the marker,
	 * or <code>null</code> if there is no applicable resource. This
	 * queries the editor's input using <code>getAdapter(IResource.class)</code>.
	 *
	 * @return the resource to which to attach the newly created marker
	 */
	protected IResource getResource() {
		IEditorInput input = fTextEditor.getEditorInput();
		return (IResource) ((IAdaptable) input).getAdapter(IResource.class);
	}

}