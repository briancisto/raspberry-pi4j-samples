package raspisamples.matrix;


public final class MatrixUtil {
	private static SquareMatrix minor(SquareMatrix m, int row, int col) {
		SquareMatrix small = new SquareMatrix(m.getDimension() - 1);
		for (int c = 0; c < m.getDimension(); c++) {
			if (c != col) {
				for (int r = 0; r < m.getDimension(); r++) {
					if (r != row) {
						small.setElementAt(((r < row) ? r : (r - 1)), ((c < col) ? c : (c - 1)), m.getElementAt(r, c));
					}
				}
			}
		}
		return small;
	}

	public static SquareMatrix comatrix(SquareMatrix m) {
		SquareMatrix co = new SquareMatrix(m.getDimension());
		for (int r = 0; r < m.getDimension(); r++) {
			for (int c = 0; c < m.getDimension(); c++) {
				co.setElementAt(r, c, determinant(minor(m, r, c)) * Math.pow((-1), (r + c + 2)));  // r+c+2 = (r+1) + (c+1)...
			}
		}
		return co;
	}

	public static SquareMatrix transposed(SquareMatrix m) {
		SquareMatrix t = new SquareMatrix(m.getDimension());
		// Replace line with columns.
		int r, c;
		for (r = 0; r < m.getDimension(); r++) {
			for (c = 0; c < m.getDimension(); c++) {
				t.setElementAt(r, c, m.getElementAt(c, r));
			}
		}
		return t;
	}

	public static SquareMatrix multiply(SquareMatrix m, double n) {
		SquareMatrix res = new SquareMatrix(m.getDimension());
		int r, c;

		for (r = 0; r < m.getDimension(); r++) {
			for (c = 0; c < m.getDimension(); c++) {
				res.setElementAt(r, c, m.getElementAt(r, c) * n);
			}
		}
		return res;
	}

	public static double determinant(SquareMatrix m) {
		double v = 0.0;

		if (m.getDimension() == 1) {
			v = m.getElementAt(0, 0);
		} else {
			// C : column in Major
			for (int C = 0; C < m.getDimension(); C++) { // Walk thru first line
				// Minor's determinant
				double minDet = determinant(minor(m, 0, C));
				v += (m.getElementAt(0, C) * minDet * Math.pow((-1.0), C + 1 + 1)); // line C, column 1
			}
		}
		return v;
	}

	public static SquareMatrix invert(SquareMatrix m) {
		return multiply(transposed(comatrix(m)), (1.0 / determinant(m)));
	}
}