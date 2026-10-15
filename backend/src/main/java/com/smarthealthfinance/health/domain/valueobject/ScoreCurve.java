package com.smarthealthfinance.health.domain.valueobject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Curva de nota por pontos (ADR-0017 §1): interpolação linear entre pontos consecutivos; abaixo do primeiro vale a
 * nota do primeiro, acima do último vale a do último. Resultado com 4 casas (HALF_EVEN), sem ponto flutuante.
 */
public final class ScoreCurve {

    public static final int SCALE = 4;

    public record Point(BigDecimal x, BigDecimal y) {

        public Point {
            Objects.requireNonNull(x, "x");
            Objects.requireNonNull(y, "y");
        }
    }

    private final List<Point> points;

    private ScoreCurve(List<Point> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("A curva precisa de pelo menos 2 pontos");
        }
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).x().compareTo(points.get(i - 1).x()) <= 0) {
                throw new IllegalArgumentException("Os pontos da curva devem ter x crescente");
            }
        }
        this.points = List.copyOf(points);
    }

    /** @param xy pares x, y como texto decimal, ex.: {@code of("-0.10", "0", "0", "30")} */
    public static ScoreCurve of(String... xy) {
        if (xy.length % 2 != 0) {
            throw new IllegalArgumentException("Pares x, y incompletos");
        }
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            points.add(new Point(new BigDecimal(xy[i]), new BigDecimal(xy[i + 1])));
        }
        return new ScoreCurve(points);
    }

    public List<Point> points() {
        return points;
    }

    public BigDecimal score(BigDecimal x) {
        Objects.requireNonNull(x, "x");
        Point first = points.getFirst();
        if (x.compareTo(first.x()) <= 0) {
            return first.y().setScale(SCALE, RoundingMode.HALF_EVEN);
        }
        for (int i = 1; i < points.size(); i++) {
            Point left = points.get(i - 1);
            Point right = points.get(i);
            if (x.compareTo(right.x()) <= 0) {
                BigDecimal slope = right.y().subtract(left.y());
                BigDecimal offset = x.subtract(left.x()).multiply(slope);
                return left.y().add(offset.divide(right.x().subtract(left.x()), SCALE, RoundingMode.HALF_EVEN))
                        .setScale(SCALE, RoundingMode.HALF_EVEN);
            }
        }
        return points.getLast().y().setScale(SCALE, RoundingMode.HALF_EVEN);
    }
}
