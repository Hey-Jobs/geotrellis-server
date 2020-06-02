package geotrellis.stac.api

import com.azavea.stac4s.{Bbox, TemporalExtent, TwoDimBbox}
import geotrellis.store.query.QueryF
import higherkindness.droste.Algebra
import cats.syntax.option._
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import geotrellis.vector._

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.CoordinateFilter

case class SearchFilters(
  bbox: Option[Bbox] = None,
  datetime: Option[TemporalExtent] = None,
  intersects: Option[Geometry] = None,
  collections: List[String] = Nil,
  items: List[String] = Nil,
  limit: Option[Int] = None,
  next: Option[String] = None,
  query: JsonObject = JsonObject.empty
) {
  def and(other: SearchFilters): SearchFilters = {
    SearchFilters(
      bbox = bbox orElse other.bbox,
      datetime = (datetime, other.datetime) match {
        case (Some(left), Some(right)) =>
          val (lmin, lmax) = left.value.min -> left.value.max
          val (rmin, rmax) = right.value.min -> right.value.max
          TemporalExtent.from(List(
            List(lmin, rmin).max,
            List(lmax, rmax).min
          )).toOption
        case (l @ Some(_), _) => l
        case (_, r @ Some(_)) => r
        case _ => None
      },
      intersects = (intersects, other.intersects) match {
        case (Some(l), Some(r)) => l.intersection(r).some
        case (l @ Some(_), _) => l
        case (_,  r @ Some(_)) => r
        case _ => None
      },
      collections = (collections ++ other.collections).distinct,
      items = (collections ++ other.collections).distinct,
      limit = List(limit, other.limit).min,
      next = List(next, other.next).max,
      query = query.deepMerge(other.query)
    )
  }
}

object SearchFilters {

  /**
   * TODO: implement a fully correct logic here; we need to have an inverted geometry in the query if that is a lat lon thing.
   * */
  private class InvertCoordinateFilter extends CoordinateFilter {
    override def filter(coord: Coordinate): Unit = {
      val oldX = coord.x
      coord.x = coord.y
      coord.y = oldX
    }
  }

  implicit val searchFilterDecoder: Decoder[SearchFilters] = { c =>
    for {
      bbox <- c.downField("bbox").as[Option[Bbox]]
      datetime <- c.downField("datetime").as[Option[TemporalExtent]]
      intersects <- c.downField("intersects").as[Option[Geometry]]
      collectionsOption <- c.downField("collections").as[Option[List[String]]]
      itemsOption <- c.downField("items").as[Option[List[String]]]
      limit <- c.downField("limit").as[Option[Int]]
      next <- c.downField("next").as[Option[String]]
      query <- c.get[JsonObject]("query")
    } yield {
      SearchFilters(
        bbox,
        datetime,
        intersects,
        collectionsOption.getOrElse(Nil),
        itemsOption.getOrElse(Nil),
        limit,
        next,
        query
      )
    }
  }


  implicit val searchFilterEncoder: Encoder[SearchFilters] = { filters =>
    deriveEncoder[SearchFilters].apply(filters.copy(intersects = filters.intersects.map { g => g.apply(new InvertCoordinateFilter()); g }))
  }

  // dont use the word demo it confuses Eugene
  // and me
  //
  // Question for James and Chris
  // can it sort htwe return items?
  // mb it a franklin?>
  // limit + sort can fix
  // sort by time, to get the most recent?
  //
  // how this should look like?
  import geotrellis.store.query.QueryF._
  def algebra: Algebra[QueryF, SearchFilters] = Algebra {
    case Nothing() => SearchFilters() // unsupported node
    case All() => SearchFilters()
    case WithName(name) => SearchFilters(query = Map("layer:ids" -> Map("superset" -> List(name).asJson).asJson).asJsonObject)
    case WithNames(names) => SearchFilters(query = Map("layer:ids" -> Map("superset" -> names.asJson).asJson).asJsonObject)
    case At(t, _) => SearchFilters(datetime = TemporalExtent(t.toInstant, None).some)
    case Between(t1, t2, _) => SearchFilters(datetime = TemporalExtent(t1.toInstant, t2.toInstant).some)
    case Intersects(e) => SearchFilters(intersects = e.extent.toPolygon.some)
    case Covers(e) => SearchFilters(
      bbox = TwoDimBbox(e.extent.xmin, e.extent.xmax, e.extent.ymin, e.extent.ymax).some
    ) // unsupported node ?
    case Contains(_) => SearchFilters() // unsupported node
    case And(e1, e2) => e1 and e2
    case Or(_, _) => SearchFilters() // unsupported node
  }
}